/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.server.impl.store;

import java.io.IOException;
import java.math.BigInteger;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.api.NameId;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.OperationException.ErrorCode;
import org.xipki.ca.api.publisher.CertificateInfo;
import org.xipki.ca.api.RequestType;
import org.xipki.ca.api.CertWithDbId;
import org.xipki.ca.server.impl.CaIdNameMap;
import org.xipki.ca.server.impl.CertRevInfoWithSerial;
import org.xipki.ca.server.impl.CertStatus;
import org.xipki.ca.server.impl.DbSchemaInfo;
import org.xipki.ca.server.impl.KnowCertResult;
import org.xipki.ca.server.impl.SerialWithId;
import org.xipki.ca.server.impl.UniqueIdGenerator;
import org.xipki.ca.server.impl.util.CaUtil;
import org.xipki.ca.server.impl.util.PasswordHash;
import org.xipki.ca.server.mgmt.api.CaHasUserEntry;
import org.xipki.ca.server.mgmt.api.CertListInfo;
import org.xipki.ca.server.mgmt.api.CertListOrderBy;
import org.xipki.common.util.Base64;
import org.xipki.common.util.CollectionUtil;
import org.xipki.common.util.LogUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.security.CertRevocationInfo;
import org.xipki.security.CrlReason;
import org.xipki.security.FpIdCalculator;
import org.xipki.security.HashAlgo;
import org.xipki.security.ObjectIdentifiers;
import org.xipki.security.X509Cert;
import org.xipki.security.util.X509Util;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CertStore {

  private static final Logger LOG = LoggerFactory.getLogger(CertStore.class);

  private static final ErrorCode DB_FAILURE = ErrorCode.DATABASE_FAILURE;

  private final DataSourceWrapper datasource;

  @SuppressWarnings("unused")
  private final int dbSchemaVersion;

  private final int maxX500nameLen;

  private final UniqueIdGenerator idGenerator;

  private final SQLs sqls;

  public CertStore(DataSourceWrapper datasource, UniqueIdGenerator idGenerator)
      throws DataAccessException {
    this.datasource = ParamUtil.requireNonNull("datasource", datasource);
    this.idGenerator = ParamUtil.requireNonNull("idGenerator", idGenerator);

    DbSchemaInfo dbSchemaInfo = new DbSchemaInfo(datasource);
    String str = dbSchemaInfo.variableValue("VERSION");
    this.dbSchemaVersion = Integer.parseInt(str);
    str = dbSchemaInfo.variableValue("X500NAME_MAXLEN");
    this.maxX500nameLen = Integer.parseInt(str);
    this.sqls = new SQLs(datasource);
  } // constructor

  public boolean addCert(CertificateInfo certInfo) {
    ParamUtil.requireNonNull("certInfo", certInfo);
    try {
      addCert(certInfo.getIssuer(), certInfo.getCert(), certInfo.getSubjectPublicKey(),
          certInfo.getProfile(), certInfo.getRequestor(), certInfo.getUser(), certInfo.getReqType(),
          certInfo.getTransactionId(), certInfo.getRequestedSubject());
    } catch (Exception ex) {
      LOG.error("could not save certificate {}: {}. Message: {}",
          new Object[]{certInfo.getCert().getSubject(),
              Base64.encodeToString(certInfo.getCert().getEncodedCert(), true), ex.getMessage()});
      LOG.debug("error", ex);
      return false;
    }

    return true;
  }

  private void addCert(NameId ca, CertWithDbId certificate, byte[] encodedSubjectPublicKey,
      NameId certProfile, NameId requestor, Integer userId, RequestType reqType,
      byte[] transactionId, X500Name reqSubject) throws DataAccessException, OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("certificate", certificate);
    ParamUtil.requireNonNull("certProfile", certProfile);
    ParamUtil.requireNonNull("requestor", requestor);

    long certId = idGenerator.nextId();
    X509Certificate cert = certificate.getCert();

    long fpPk = FpIdCalculator.hash(encodedSubjectPublicKey);
    String subjectText = X509Util.cutText(certificate.getSubject(), maxX500nameLen);
    long fpSubject = X509Util.fpCanonicalizedName(cert.getSubjectX500Principal());

    String reqSubjectText = null;
    Long fpReqSubject = null;
    if (reqSubject != null) {
      fpReqSubject = X509Util.fpCanonicalizedName(reqSubject);
      if (fpSubject == fpReqSubject) {
        fpReqSubject = null;
      } else {
        reqSubjectText = X509Util.cutX500Name(CaUtil.sortX509Name(reqSubject), maxX500nameLen);
      }
    }

    String b64FpCert = base64Fp(certificate.getEncodedCert());
    String b64Cert = Base64.encodeToString(certificate.getEncodedCert());
    String tid = (transactionId == null) ? null : Base64.encodeToString(transactionId);

    Connection conn = null;
    PreparedStatement[] pss = borrowPreparedStatements(SQLs.SQL_ADD_CERT, SQLs.SQL_ADD_CRAW);

    try {
      PreparedStatement psAddcert = pss[0];
      // all statements have the same connection
      conn = psAddcert.getConnection();

      // cert
      int idx = 2;
      psAddcert.setLong(idx++, System.currentTimeMillis() / 1000); // currentTimeSeconds
      psAddcert.setString(idx++, cert.getSerialNumber().toString(16));
      psAddcert.setString(idx++, subjectText);
      psAddcert.setLong(idx++, fpSubject);
      setLong(psAddcert, idx++, fpReqSubject);
      psAddcert.setLong(idx++, cert.getNotBefore().getTime() / 1000); // notBeforeSeconds
      psAddcert.setLong(idx++, cert.getNotAfter().getTime() / 1000); // notAfterSeconds
      setBoolean(psAddcert, idx++, false);
      psAddcert.setInt(idx++, certProfile.getId());
      psAddcert.setInt(idx++, ca.getId());
      setInt(psAddcert, idx++, requestor.getId());
      setInt(psAddcert, idx++, userId);
      psAddcert.setLong(idx++, fpPk);
      boolean isEeCert = cert.getBasicConstraints() == -1;
      psAddcert.setInt(idx++, isEeCert ? 1 : 0);
      psAddcert.setInt(idx++, reqType.getCode());
      psAddcert.setString(idx++, tid);

      // rawcert
      PreparedStatement psAddRawcert = pss[1];

      idx = 2;
      psAddRawcert.setString(idx++, b64FpCert);
      psAddRawcert.setString(idx++, reqSubjectText);
      psAddRawcert.setString(idx++, b64Cert);

      certificate.setCertId(certId);

      psAddcert.setLong(1, certId);
      psAddRawcert.setLong(1, certId);

      final boolean origAutoCommit = conn.getAutoCommit();
      conn.setAutoCommit(false);

      String sql = null;
      try {
        sql = SQLs.SQL_ADD_CERT;
        psAddcert.executeUpdate();

        sql = SQLs.SQL_ADD_CRAW;
        psAddRawcert.executeUpdate();

        sql = "(commit add cert to CA certstore)";
        conn.commit();
      } catch (Throwable th) {
        conn.rollback();
        // more secure
        datasource.deleteFromTable(null, "CRAW", "CID", certId);
        datasource.deleteFromTable(null, "CERT", "ID", certId);

        if (th instanceof SQLException) {
          LOG.error("datasource {} could not add certificate with id {}: {}",
              datasource.getName(), certId, th.getMessage());
          throw datasource.translate(sql, (SQLException) th);
        } else {
          throw new OperationException(ErrorCode.SYSTEM_FAILURE, th);
        }
      } finally {
        conn.setAutoCommit(origAutoCommit);
      }
    } catch (SQLException ex) {
      throw datasource.translate(null, ex);
    } finally {
      try {
        for (PreparedStatement ps : pss) {
          releaseStatement(ps);
        }
      } finally {
        if (conn != null) {
          datasource.returnConnection(conn);
        }
      }
    }
  } // method addCert

  public void addToPublishQueue(NameId publisher, long certId, NameId ca)
      throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    final String sql = SQLs.SQL_INSERT_PUBLISHQUEUE;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setInt(1, publisher.getId());
      ps.setInt(2, ca.getId());
      ps.setLong(3, certId);
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  }

  public void removeFromPublishQueue(NameId publisher, long certId) throws OperationException {
    final String sql = SQLs.SQL_REMOVE_PUBLISHQUEUE;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setInt(1, publisher.getId());
      ps.setLong(2, certId);
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  }

  public long getMaxIdOfDeltaCrlCache(NameId ca) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    final String sql = SQLs.SQL_MAXID_DELTACRL_CACHE;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setInt(1, ca.getId());
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) {
        return 0;
      }
      return rs.getLong(1);
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  }

  public void clearDeltaCrlCache(NameId ca, long maxId) throws OperationException {
    final String sql = SQLs.SQL_CLEAR_DELTACRL_CACHE;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setLong(1, maxId + 1);
      ps.setInt(2, ca.getId());
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  }

  public void clearPublishQueue(NameId ca, NameId publisher) throws OperationException {
    StringBuilder sqlBuilder = new StringBuilder(80);
    sqlBuilder.append("DELETE FROM PUBLISHQUEUE");
    if (ca != null || publisher != null) {
      sqlBuilder.append(" WHERE");
      if (ca != null) {
        sqlBuilder.append(" CA_ID=?");
        if (publisher != null) {
          sqlBuilder.append(" AND");
        }
      }
      if (publisher != null) {
        sqlBuilder.append(" PID=?");
      }
    }

    String sql = sqlBuilder.toString();
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      int idx = 1;
      if (ca != null) {
        ps.setInt(idx++, ca.getId());
      }

      if (publisher != null) {
        ps.setInt(idx++, publisher.getId());
      }
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  }

  public long getMaxCrlNumber(NameId ca) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    final String sql = SQLs.SQL_MAX_CRLNO;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setInt(1, ca.getId());
      rs = ps.executeQuery();
      if (!rs.next()) {
        return 0;
      }
      long maxCrlNumber = rs.getLong(1);
      return (maxCrlNumber < 0) ? 0 : maxCrlNumber;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  public Long getThisUpdateOfCurrentCrl(NameId ca) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    final String sql = SQLs.SQL_MAX_THISUPDAATE_CRL;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setInt(1, ca.getId());
      rs = ps.executeQuery();
      if (!rs.next()) {
        return 0L;
      }
      return rs.getLong(1);
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  public boolean hasCrl(NameId ca) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    final String sql = sqls.sqlCaHasCrl;
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      ps = borrowPreparedStatement(sql);
      ps.setInt(1, ca.getId());
      rs = ps.executeQuery();
      return rs.next();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  public void addCrl(NameId ca, X509CRL crl) throws OperationException, CRLException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("crl", crl);

    byte[] encodedExtnValue = crl.getExtensionValue(Extension.cRLNumber.getId());
    Long crlNumber = null;
    if (encodedExtnValue != null) {
      byte[] extnValue = DEROctetString.getInstance(encodedExtnValue).getOctets();
      crlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue().longValue();
    }

    encodedExtnValue = crl.getExtensionValue(Extension.deltaCRLIndicator.getId());
    Long baseCrlNumber = null;
    if (encodedExtnValue != null) {
      byte[] extnValue = DEROctetString.getInstance(encodedExtnValue).getOctets();
      baseCrlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue().longValue();
    }

    final String sql = SQLs.SQL_ADD_CRL;
    long currentMaxCrlId;
    try {
      currentMaxCrlId = datasource.getMax(null, "CRL", "ID");
    } catch (DataAccessException ex) {
      throw new OperationException(DB_FAILURE, ex.getMessage());
    }
    long crlId = currentMaxCrlId + 1;

    String b64Crl = Base64.encodeToString(crl.getEncoded());

    PreparedStatement ps = null;

    try {
      ps = borrowPreparedStatement(sql);

      int idx = 1;
      ps.setLong(idx++, crlId);
      ps.setInt(idx++, ca.getId());
      setLong(ps, idx++, crlNumber);
      Date date = crl.getThisUpdate();
      ps.setLong(idx++, date.getTime() / 1000);
      setDateSeconds(ps, idx++, crl.getNextUpdate());
      setBoolean(ps, idx++, (baseCrlNumber != null));
      setLong(ps, idx++, baseCrlNumber);
      ps.setString(idx++, b64Crl);

      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  } // method addCrl

  public CertWithRevocationInfo revokeCert(NameId ca, BigInteger serialNumber,
      CertRevocationInfo revInfo, boolean force, boolean publishToDeltaCrlCache,
      CaIdNameMap idNameMap) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("serialNumber", serialNumber);
    ParamUtil.requireNonNull("revInfo", revInfo);

    CertWithRevocationInfo certWithRevInfo =
        getCertWithRevocationInfo(ca, serialNumber, idNameMap);
    if (certWithRevInfo == null) {
      LOG.warn("certificate with CA={} and serialNumber={} does not exist",
          ca.getName(), LogUtil.formatCsn(serialNumber));
      return null;
    }

    CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
    if (currentRevInfo != null) {
      CrlReason currentReason = currentRevInfo.getReason();
      if (currentReason == CrlReason.CERTIFICATE_HOLD) {
        if (revInfo.getReason() == CrlReason.CERTIFICATE_HOLD) {
          throw new OperationException(ErrorCode.CERT_REVOKED,
              "certificate already revoked with the requested reason "
              + currentReason.getDescription());
        } else {
          revInfo.setRevocationTime(currentRevInfo.getRevocationTime());
          revInfo.setInvalidityTime(currentRevInfo.getInvalidityTime());
        }
      } else if (!force) {
        throw new OperationException(ErrorCode.CERT_REVOKED,
          "certificate already revoked with reason " + currentReason.getDescription());
      }
    }

    Long invTimeSeconds = null;
    if (revInfo.getInvalidityTime() != null) {
      invTimeSeconds = revInfo.getInvalidityTime().getTime() / 1000;
    }

    PreparedStatement ps = borrowPreparedStatement(SQLs.SQL_REVOKE_CERT);
    try {
      int idx = 1;
      ps.setLong(idx++, System.currentTimeMillis() / 1000);
      setBoolean(ps, idx++, true);
      ps.setLong(idx++, revInfo.getRevocationTime().getTime() / 1000); // revTimeSeconds
      setLong(ps, idx++, invTimeSeconds);
      ps.setInt(idx++, revInfo.getReason().getCode());
      ps.setLong(idx++, certWithRevInfo.getCert().getCertId().longValue()); // certId

      int count = ps.executeUpdate();
      if (count != 1) {
        String message = (count > 1)
            ? count + " rows modified, but exactly one is expected"
            : "no row is modified, but exactly one is expected";
        throw new OperationException(ErrorCode.SYSTEM_FAILURE, message);
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE,
          datasource.translate(SQLs.SQL_REVOKE_CERT, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }

    if (publishToDeltaCrlCache) {
      publishToDeltaCrlCache(ca, certWithRevInfo.getCert().getCert().getSerialNumber());
    }

    certWithRevInfo.setRevInfo(revInfo);
    return certWithRevInfo;
  } // method revokeCert

  public CertWithRevocationInfo revokeSuspendedCert(NameId ca, BigInteger serialNumber,
      CrlReason reason, boolean publishToDeltaCrlCache, CaIdNameMap idNameMap)
      throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("serialNumber", serialNumber);
    ParamUtil.requireNonNull("reason", reason);

    CertWithRevocationInfo certWithRevInfo =
        getCertWithRevocationInfo(ca, serialNumber, idNameMap);
    if (certWithRevInfo == null) {
      LOG.warn("certificate with CA={} and serialNumber={} does not exist",
          ca.getName(), LogUtil.formatCsn(serialNumber));
      return null;
    }

    CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
    if (currentRevInfo == null) {
      throw new OperationException(ErrorCode.CERT_UNREVOKED, "certificate is not revoked");
    }

    CrlReason currentReason = currentRevInfo.getReason();
    if (currentReason != CrlReason.CERTIFICATE_HOLD) {
      throw new OperationException(ErrorCode.CERT_REVOKED,
          "certificate is revoked but not with reason "
          + CrlReason.CERTIFICATE_HOLD.getDescription());
    }

    PreparedStatement ps = borrowPreparedStatement(SQLs.SQL_REVOKE_SUSPENDED_CERT);
    try {
      int idx = 1;
      ps.setLong(idx++, System.currentTimeMillis() / 1000);
      ps.setInt(idx++, reason.getCode());
      ps.setLong(idx++, certWithRevInfo.getCert().getCertId().longValue()); // certId

      int count = ps.executeUpdate();
      if (count != 1) {
        String message = (count > 1)
            ? count + " rows modified, but exactly one is expected"
            : "no row is modified, but exactly one is expected";
        throw new OperationException(ErrorCode.SYSTEM_FAILURE, message);
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE,
          datasource.translate(SQLs.SQL_REVOKE_CERT, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }

    if (publishToDeltaCrlCache) {
      publishToDeltaCrlCache(ca, certWithRevInfo.getCert().getCert().getSerialNumber());
    }

    currentRevInfo.setReason(reason);
    return certWithRevInfo;
  } // method revokeSuspendedCert

  public CertWithDbId unrevokeCert(NameId ca, BigInteger serialNumber, boolean force,
      boolean publishToDeltaCrlCache, CaIdNameMap idNamMap) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("serialNumber", serialNumber);

    CertWithRevocationInfo certWithRevInfo =
        getCertWithRevocationInfo(ca, serialNumber, idNamMap);
    if (certWithRevInfo == null) {
      LOG.warn("certificate with CA={} and serialNumber={} does not exist",
          ca.getName(), LogUtil.formatCsn(serialNumber));
      return null;
    }

    CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
    if (currentRevInfo == null) {
      throw new OperationException(ErrorCode.CERT_UNREVOKED, "certificate is not revoked");
    }

    CrlReason currentReason = currentRevInfo.getReason();
    if (!force) {
      if (currentReason != CrlReason.CERTIFICATE_HOLD) {
        throw new OperationException(ErrorCode.NOT_PERMITTED,
            "could not unrevoke certificate revoked with reason "
            + currentReason.getDescription());
      }
    }

    final String sql = "UPDATE CERT SET LUPDATE=?,REV=?,RT=?,RIT=?,RR=? WHERE ID=?";

    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      int idx = 1;
      ps.setLong(idx++, System.currentTimeMillis() / 1000); // currentTimeSeconds
      setBoolean(ps, idx++, false);
      ps.setNull(idx++, Types.INTEGER);
      ps.setNull(idx++, Types.INTEGER);
      ps.setNull(idx++, Types.INTEGER);
      ps.setLong(idx++, certWithRevInfo.getCert().getCertId().longValue()); // certId

      int count = ps.executeUpdate();
      if (count != 1) {
        String message = (count > 1)
            ? count + " rows modified, but exactly one is expected"
            : "no row is modified, but exactly one is expected";
        throw new OperationException(ErrorCode.SYSTEM_FAILURE, message);
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }

    if (publishToDeltaCrlCache) {
      publishToDeltaCrlCache(ca, certWithRevInfo.getCert().getCert().getSerialNumber());
    }

    return certWithRevInfo.getCert();
  } // method unrevokeCert

  private void publishToDeltaCrlCache(NameId ca, BigInteger serialNumber)
      throws OperationException {
    ParamUtil.requireNonNull("serialNumber", serialNumber);

    final String sql = SQLs.SQL_ADD_DELTACRL_CACHE;
    PreparedStatement ps = null;
    try {
      long id = idGenerator.nextId();
      ps = borrowPreparedStatement(sql);
      ps.setLong(1, id);
      ps.setInt(2, ca.getId());
      ps.setString(3, serialNumber.toString(16));
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  }

  public void removeCertificate(NameId ca, BigInteger serialNumber) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("serialNumber", serialNumber);

    final String sql = SQLs.SQL_REMOVE_CERT;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());
      ps.setString(2, serialNumber.toString(16));

      int count = ps.executeUpdate();
      if (count != 1) {
        String message = (count > 1)
            ? count + " rows modified, but exactly one is expected"
            : "no row is modified, but exactly one is expected";
        throw new OperationException(ErrorCode.SYSTEM_FAILURE, message);
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  } // method removeCertificate

  public List<Long> getPublishQueueEntries(NameId ca, NameId publisher, int numEntries)
      throws OperationException {
    final String sql = sqls.getSqlCidFromPublishQueue(numEntries);
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, publisher.getId());
      ps.setInt(2, ca.getId());
      rs = ps.executeQuery();
      List<Long> ret = new ArrayList<>();
      while (rs.next() && ret.size() < numEntries) {
        long certId = rs.getLong("CID");
        if (!ret.contains(certId)) {
          ret.add(certId);
        }
      }
      return ret;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getPublishQueueEntries

  public long getCountOfCerts(NameId ca, boolean onlyRevoked) throws OperationException {
    final String sql = onlyRevoked ? "SELECT COUNT(*) FROM CERT WHERE CA_ID=? AND REV=1"
                    : "SELECT COUNT(*) FROM CERT WHERE CA_ID=?";

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());
      rs = ps.executeQuery();
      rs.next();
      return rs.getLong(1);
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  public List<SerialWithId> getSerialNumbers(NameId ca,  long startId, int numEntries,
      boolean onlyRevoked) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireMin("numEntries", numEntries, 1);

    final String sql = sqls.getSqlSerials(numEntries, onlyRevoked);

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setLong(1, startId - 1);
      ps.setInt(2, ca.getId());
      rs = ps.executeQuery();
      List<SerialWithId> ret = new ArrayList<>();
      while (rs.next() && ret.size() < numEntries) {
        long id = rs.getLong("ID");
        String serial = rs.getString("SN");
        ret.add(new SerialWithId(id, new BigInteger(serial, 16)));
      }
      return ret;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getSerialNumbers

  public List<SerialWithId> getSerialNumbers(NameId ca, Date notExpiredAt, long startId,
      int numEntries, boolean onlyRevoked, boolean onlyCaCerts, boolean onlyUserCerts)
      throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireMin("numEntries", numEntries, 1);

    if (onlyCaCerts && onlyUserCerts) {
      throw new IllegalArgumentException("onlyCaCerts and onlyUserCerts cannot be both of true");
    }
    boolean withEe = onlyCaCerts || onlyUserCerts;
    final String sql = sqls.getSqlSerials(numEntries, notExpiredAt, onlyRevoked, withEe);

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      int idx = 1;
      ps.setLong(idx++, startId - 1);
      ps.setInt(idx++, ca.getId());
      if (notExpiredAt != null) {
        ps.setLong(idx++, notExpiredAt.getTime() / 1000 + 1);
      }
      if (withEe) {
        setBoolean(ps, idx++, onlyUserCerts);
      }
      rs = ps.executeQuery();
      List<SerialWithId> ret = new ArrayList<>();
      while (rs.next() && ret.size() < numEntries) {
        long id = rs.getLong("ID");
        String serial = rs.getString("SN");
        ret.add(new SerialWithId(id, new BigInteger(serial, 16)));
      }
      return ret;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getSerialNumbers

  public List<BigInteger> getExpiredSerialNumbers(NameId ca, long expiredAt, int numEntries)
      throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireMin("numEntries", numEntries, 1);

    final String sql = sqls.getSqlExpiredSerials(numEntries);

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());
      ps.setLong(2, expiredAt);
      rs = ps.executeQuery();
      List<BigInteger> ret = new ArrayList<>();
      while (rs.next() && ret.size() < numEntries) {
        String serial = rs.getString("SN");
        ret.add(new BigInteger(serial, 16));
      }
      return ret;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getExpiredSerialNumbers

  public List<BigInteger> getSuspendedCertSerials(NameId ca, long latestLastUpdate, int numEntries)
      throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireMin("numEntries", numEntries, 1);

    final String sql = sqls.getSqlSuspendedSerials(numEntries);
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());
      ps.setLong(2, latestLastUpdate + 1);
      ps.setInt(3, CrlReason.CERTIFICATE_HOLD.getCode());
      rs = ps.executeQuery();
      List<BigInteger> ret = new ArrayList<>();
      while (rs.next() && ret.size() < numEntries) {
        String str = rs.getString("SN");
        ret.add(new BigInteger(str, 16));
      }
      return ret;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getSuspendedCertIds

  public byte[] getEncodedCrl(NameId ca, BigInteger crlNumber) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    String sql = (crlNumber == null) ? sqls.sqlCrl : sqls.sqlCrlWithNo;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    String b64Crl = null;
    try {
      int idx = 1;
      ps.setInt(idx++, ca.getId());
      if (crlNumber != null) {
        ps.setLong(idx++, crlNumber.longValue());
      }
      rs = ps.executeQuery();
      long currentThisUpdate = 0;
      // iterate all entries to make sure that the latest CRL will be returned
      while (rs.next()) {
        long thisUpdate = rs.getLong("THISUPDATE");
        if (thisUpdate >= currentThisUpdate) {
          b64Crl = rs.getString("CRL");
          currentThisUpdate = thisUpdate;
        }
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    return (b64Crl == null) ? null : Base64.decodeFast(b64Crl);
  } // method getEncodedCrl

  public int cleanupCrls(NameId ca, int numCrls) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireMin("numCrls", numCrls, 1);

    String sql = "SELECT CRL_NO FROM CRL WHERE CA_ID=? AND DELTACRL=?";
    PreparedStatement ps = borrowPreparedStatement(sql);
    List<Integer> crlNumbers = new LinkedList<>();
    ResultSet rs = null;
    try {
      ps.setInt(1, ca.getId());
      setBoolean(ps, 2, false);
      rs = ps.executeQuery();

      while (rs.next()) {
        int crlNumber = rs.getInt("CRL_NO");
        crlNumbers.add(crlNumber);
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    int size = crlNumbers.size();
    Collections.sort(crlNumbers);

    int numCrlsToDelete = size - numCrls;
    if (numCrlsToDelete < 1) {
      return 0;
    }

    int crlNumber = crlNumbers.get(numCrlsToDelete - 1);
    sql = "DELETE FROM CRL WHERE CA_ID=? AND CRL_NO<?";
    ps = borrowPreparedStatement(sql);

    try {
      int idx = 1;
      ps.setInt(idx++, ca.getId());
      ps.setInt(idx++, crlNumber + 1);
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }

    return numCrlsToDelete;
  } // method cleanupCrls

  public CertificateInfo getCertForId(NameId ca, X509Cert caCert, long certId,
      CaIdNameMap idNameMap) throws OperationException, CertificateException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("caCert", caCert);
    ParamUtil.requireNonNull("idNameMap", idNameMap);

    final String sql = sqls.sqlCertForId;

    String b64Cert;
    int certprofileId;
    int requestorId;
    boolean revoked;
    int revReason = 0;
    long revTime = 0;
    long revInvTime = 0;

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setLong(1, certId);
      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }
      b64Cert = rs.getString("CERT");
      certprofileId = rs.getInt("PID");
      requestorId = rs.getInt("RID");
      revoked = rs.getBoolean("REV");
      if (revoked) {
        revReason = rs.getInt("RR");
        revTime = rs.getLong("RT");
        revInvTime = rs.getLong("RIT");
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    byte[] encodedCert = Base64.decodeFast(b64Cert);
    X509Certificate cert = X509Util.parseCert(encodedCert);
    CertWithDbId certWithMeta = new CertWithDbId(cert, encodedCert);
    certWithMeta.setCertId(certId);
    CertificateInfo certInfo = new CertificateInfo(certWithMeta, ca, caCert,
        cert.getPublicKey().getEncoded(), idNameMap.getCertprofile(certprofileId),
        idNameMap.getRequestor(requestorId));
    if (!revoked) {
      return certInfo;
    }
    Date invalidityTime = (revInvTime == 0 || revInvTime == revTime) ? null
        : new Date(revInvTime * 1000);
    CertRevocationInfo revInfo = new CertRevocationInfo(revReason,
        new Date(revTime * 1000), invalidityTime);
    certInfo.setRevocationInfo(revInfo);
    return certInfo;
  } // method getCertForId

  private CertWithDbId getCertForId(long certId) throws OperationException, OperationException {
    final String sql = sqls.sqlRawCertForId;

    String b64Cert;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setLong(1, certId);
      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }
      b64Cert = rs.getString("CERT");
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    if (b64Cert == null) {
      return null;
    }
    byte[] encodedCert = Base64.decodeFast(b64Cert);
    X509Certificate cert;
    try {
      cert = X509Util.parseCert(encodedCert);
    } catch (CertificateException ex) {
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }
    return new CertWithDbId(cert, encodedCert);
  } // method getCertForId

  public CertWithRevocationInfo getCertWithRevocationInfo(NameId ca, BigInteger serial,
      CaIdNameMap idNameMap) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("serial", serial);
    ParamUtil.requireNonNull("idNameMap", idNameMap);

    final String sql = sqls.sqlCertWithRevInfo;

    long certId;
    String b64Cert;
    boolean revoked;
    int revReason = 0;
    long revTime = 0;
    long revInvTime = 0;
    int certprofileId = 0;

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      int idx = 1;
      ps.setInt(idx++, ca.getId());
      ps.setString(idx++, serial.toString(16));
      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }
      certId = rs.getLong("ID");
      b64Cert = rs.getString("CERT");
      certprofileId = rs.getInt("PID");

      revoked = rs.getBoolean("REV");
      if (revoked) {
        revReason = rs.getInt("RR");
        revTime = rs.getLong("RT");
        revInvTime = rs.getLong("RIT");
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }

    byte[] certBytes = Base64.decodeFast(b64Cert);
    X509Certificate cert;
    try {
      cert = X509Util.parseCert(certBytes);
    } catch (CertificateException ex) {
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }

    CertRevocationInfo revInfo = null;
    if (revoked) {
      Date invalidityTime = (revInvTime == 0) ? null : new Date(1000 * revInvTime);
      revInfo = new CertRevocationInfo(revReason, new Date(1000 * revTime), invalidityTime);
    }

    CertWithDbId certWithMeta = new CertWithDbId(cert, certBytes);
    certWithMeta.setCertId(certId);

    String profileName = idNameMap.getCertprofileName(certprofileId);
    CertWithRevocationInfo ret = new CertWithRevocationInfo();
    ret.setCertprofile(profileName);
    ret.setCert(certWithMeta);
    ret.setRevInfo(revInfo);
    return ret;
  } // method getCertWithRevocationInfo

  public CertificateInfo getCertificateInfo(NameId ca, X509Cert caCert, BigInteger serial,
      CaIdNameMap idNameMap) throws OperationException, CertificateException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("caCert", caCert);
    ParamUtil.requireNonNull("idNameMap", idNameMap);
    ParamUtil.requireNonNull("serial", serial);

    final String sql = sqls.sqlCertInfo;

    String b64Cert;
    boolean revoked;
    int revReason = 0;
    long revTime = 0;
    long revInvTime = 0;
    int certprofileId;
    int requestorId;

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      int idx = 1;
      ps.setInt(idx++, ca.getId());
      ps.setString(idx++, serial.toString(16));
      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }
      b64Cert = rs.getString("CERT");
      certprofileId = rs.getInt("PID");
      requestorId = rs.getInt("RID");
      revoked = rs.getBoolean("REV");
      if (revoked) {
        revReason = rs.getInt("RR");
        revTime = rs.getLong("RT");
        revInvTime = rs.getLong("RIT");
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    try {
      byte[] encodedCert = Base64.decodeFast(b64Cert);
      X509Certificate cert = X509Util.parseCert(encodedCert);

      CertWithDbId certWithMeta = new CertWithDbId(cert, encodedCert);

      byte[] subjectPublicKeyInfo = Certificate.getInstance(encodedCert)
          .getTBSCertificate().getSubjectPublicKeyInfo().getEncoded();
      CertificateInfo certInfo = new CertificateInfo(certWithMeta, ca,
          caCert, subjectPublicKeyInfo, idNameMap.getCertprofile(certprofileId),
          idNameMap.getRequestor(requestorId));

      if (!revoked) {
        return certInfo;
      }

      Date invalidityTime = (revInvTime == 0) ? null : new Date(revInvTime * 1000);
      CertRevocationInfo revInfo = new CertRevocationInfo(revReason, new Date(revTime * 1000),
          invalidityTime);
      certInfo.setRevocationInfo(revInfo);
      return certInfo;
    } catch (IOException ex) {
      LOG.warn("getCertificateInfo()", ex);
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }
  } // method getCertificateInfo

  public Integer getCertProfileForCertId(NameId ca, long cid) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    final String sql = sqls.sqlCertprofileForCertId;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setLong(1, cid);
      ps.setInt(2, ca.getId());
      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }

      return rs.getInt("PID");
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getCertProfileForId

  /**
   * TODO.
   * @param subjectName Subject of Certificate or requested Subject.
   * @param transactionId will only be considered if there are more than one certificate
   *     matches the subject.
   */
  public List<X509Certificate> getCertificate(X500Name subjectName, byte[] transactionId)
      throws OperationException {
    final String sql = (transactionId != null)
        ? "SELECT ID FROM CERT WHERE TID=? AND (FP_S=? OR FP_RS=?)"
        : "SELECT ID FROM CERT WHERE FP_S=? OR FP_RS=?";

    long fpSubject = X509Util.fpCanonicalizedName(subjectName);
    List<Long> certIds = new LinkedList<Long>();

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      int idx = 1;
      if (transactionId != null) {
        ps.setString(idx++, Base64.encodeToString(transactionId));
      }
      ps.setLong(idx++, fpSubject);
      ps.setLong(idx++, fpSubject);
      rs = ps.executeQuery();

      while (rs.next()) {
        long id = rs.getLong("ID");
        certIds.add(id);
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    if (CollectionUtil.isEmpty(certIds)) {
      return Collections.emptyList();
    }

    List<X509Certificate> certs = new ArrayList<X509Certificate>(certIds.size());
    for (Long certId : certIds) {
      CertWithDbId cert = getCertForId(certId);
      if (cert != null) {
        certs.add(cert.getCert());
      }
    }

    return certs;
  } // method getCertificate

  public byte[] getCertRequest(NameId ca, BigInteger serialNumber) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("serialNumber", serialNumber);

    String sql = sqls.sqlReqIdForSerial;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    Long reqId = null;
    try {
      ps.setInt(1, ca.getId());
      ps.setString(2, serialNumber.toString(16));
      rs = ps.executeQuery();

      if (rs.next()) {
        reqId = rs.getLong("REQ_ID");
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    if (reqId == null) {
      return null;
    }

    String b64Req = null;
    sql = sqls.sqlReqForId;
    ps = borrowPreparedStatement(sql);
    try {
      ps.setLong(1, reqId);
      rs = ps.executeQuery();
      if (rs.next()) {
        b64Req = rs.getString("DATA");
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    return (b64Req == null) ? null : Base64.decodeFast(b64Req);
  }

  public List<CertListInfo> listCertificates(NameId ca, X500Name subjectPattern, Date validFrom,
      Date validTo, CertListOrderBy orderBy, int numEntries) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireMin("numEntries", numEntries, 1);

    StringBuilder sb = new StringBuilder(200);
    sb.append("SN,NBEFORE,NAFTER,SUBJECT FROM CERT WHERE CA_ID=?");
    //.append(caId)

    Integer idxNotBefore = null;
    Integer idxNotAfter = null;
    Integer idxSubject = null;

    int idx = 2;
    if (validFrom != null) {
      idxNotBefore = idx++;
      sb.append(" AND NBEFORE<?");
    }
    if (validTo != null) {
      idxNotAfter = idx++;
      sb.append(" AND NAFTER>?");
    }

    String subjectLike = null;
    if (subjectPattern != null) {
      idxSubject = idx++;
      sb.append(" AND SUBJECT LIKE ?");

      StringBuilder buffer = new StringBuilder(100);
      buffer.append("%");
      RDN[] rdns = subjectPattern.getRDNs();
      for (int i = 0; i < rdns.length; i++) {
        X500Name rdnName = new X500Name(new RDN[]{rdns[i]});
        String rdnStr = X509Util.getRfc4519Name(rdnName);
        if (rdnStr.indexOf('%') != -1) {
          throw new OperationException(ErrorCode.BAD_REQUEST,
              "the character '%' is not allowed in subjectPattern");
        }
        if (rdnStr.indexOf('*') != -1) {
          rdnStr = rdnStr.replace('*', '%');
        }
        buffer.append(rdnStr);
        buffer.append("%");
      }
      subjectLike = buffer.toString();
    }

    String sortByStr = null;
    if (orderBy != null) {
      switch (orderBy) {
        case NOT_BEFORE:
          sortByStr = "NBEFORE";
          break;
        case NOT_BEFORE_DESC:
          sortByStr = "NBEFORE DESC";
          break;
        case NOT_AFTER:
          sortByStr = "NAFTER";
          break;
        case NOT_AFTER_DESC:
          sortByStr = "NAFTER DESC";
          break;
        case SUBJECT:
          sortByStr = "SUBJECT";
          break;
        case SUBJECT_DESC:
          sortByStr = "SUBJECT DESC";
          break;
        default:
          throw new RuntimeException("unknown CertListOrderBy " + orderBy);
      }
    }

    final String sql = datasource.buildSelectFirstSql(numEntries, sortByStr, sb.toString());
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());

      if (idxNotBefore != null) {
        long time = validFrom.getTime() / 1000;
        ps.setLong(idxNotBefore, time - 1);
      }

      if (idxNotAfter != null) {
        long time = validTo.getTime() / 1000;
        ps.setLong(idxNotAfter, time);
      }

      if (idxSubject != null) {
        ps.setString(idxSubject, subjectLike);
      }

      rs = ps.executeQuery();
      List<CertListInfo> ret = new LinkedList<>();
      while (rs.next()) {
        CertListInfo info = new CertListInfo(new BigInteger(rs.getString("SN"), 16),
            rs.getString("SUBJECT"), new Date(rs.getLong("NBEFORE") * 1000),
            new Date(rs.getLong("NAFTER") * 1000));
        ret.add(info);
      }
      return ret;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  public NameId authenticateUser(String user, byte[] password) throws OperationException {
    final String sql = sqls.sqlActiveUserInfoForName;

    int id;
    String expPasswordText;

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setString(1, user);
      rs = ps.executeQuery();

      if (!rs.next()) {
        return null;
      }

      id = rs.getInt("ID");
      expPasswordText = rs.getString("PASSWORD");
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    if (StringUtil.isBlank(expPasswordText)) {
      return null;
    }

    boolean valid = PasswordHash.validatePassword(password, expPasswordText);
    return valid ? new NameId(id, user) : null;
  } // method authenticateUser

  public String getUsername(int id) throws OperationException {
    final String sql = sqls.sqlActiveUserNameForId;

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, id);
      rs = ps.executeQuery();

      if (!rs.next()) {
        return null;
      }

      return rs.getString("NAME");
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method authenticateUser

  public CaHasUserEntry getCaHasUser(NameId ca, NameId user) throws OperationException {
    final String sql = sqls.sqlCaHasUser;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());
      ps.setInt(2, user.getId());
      rs = ps.executeQuery();

      if (!rs.next()) {
        return null;
      }

      List<String> list = StringUtil.splitByComma(rs.getString("PROFILES"));
      Set<String> profiles = (list == null) ? null : new HashSet<>(list);

      CaHasUserEntry entry = new CaHasUserEntry(user);
      entry.setPermission(rs.getInt("PERMISSION"));
      entry.setProfiles(profiles);
      return entry;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  public KnowCertResult knowsCertForSerial(NameId ca, BigInteger serial) throws OperationException {
    ParamUtil.requireNonNull("serial", serial);
    final String sql = sqls.sqlKnowsCertForSerial;

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setString(1, serial.toString(16));
      ps.setInt(2, ca.getId());
      rs = ps.executeQuery();

      if (!rs.next()) {
        return KnowCertResult.UNKNOWN;
      }

      int userId = rs.getInt("UID");
      return new KnowCertResult(true, userId);
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method knowsCertForSerial

  public List<CertRevInfoWithSerial> getRevokedCerts(NameId ca, Date notExpiredAt, long startId,
      int numEntries, boolean onlyCaCerts, boolean onlyUserCerts) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireNonNull("notExpiredAt", notExpiredAt);
    ParamUtil.requireMin("numEntries", numEntries, 1);
    if (onlyCaCerts && onlyUserCerts) {
      throw new IllegalArgumentException("onlyCaCerts and onlyUserCerts cannot be both of true");
    }
    boolean withEe = onlyCaCerts || onlyUserCerts;

    String sql = sqls.getSqlRevokedCerts(numEntries, withEe);

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      int idx = 1;
      ps.setLong(idx++, startId - 1);
      ps.setInt(idx++, ca.getId());
      ps.setLong(idx++, notExpiredAt.getTime() / 1000 + 1);
      if (withEe) {
        setBoolean(ps, idx++, onlyUserCerts);
      }
      rs = ps.executeQuery();

      List<CertRevInfoWithSerial> ret = new LinkedList<>();
      while (rs.next()) {
        long revInvalidityTime = rs.getLong("RIT");
        Date invalidityTime = (revInvalidityTime == 0) ? null : new Date(1000 * revInvalidityTime);
        CertRevInfoWithSerial revInfo = new CertRevInfoWithSerial(rs.getLong("ID"),
            new BigInteger(rs.getString("SN"), 16), rs.getInt("RR"), // revReason
            new Date(1000 * rs.getLong("RT")), invalidityTime);
        ret.add(revInfo);
      }

      return ret;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getRevokedCertificates

  public List<CertRevInfoWithSerial> getCertsForDeltaCrl(NameId ca, long startId, int numEntries,
      boolean onlyCaCerts, boolean onlyUserCerts) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    ParamUtil.requireMin("numEntries", numEntries, 1);

    String sql = sqls.getSqlDeltaCrlCacheIds(numEntries);
    List<Long> ids = new LinkedList<>();
    ResultSet rs = null;

    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setLong(1, startId - 1);
      ps.setInt(2, ca.getId());
      rs = ps.executeQuery();
      while (rs.next()) {
        long id = rs.getLong("ID");
        ids.add(id);
      }
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    sql = sqls.sqlRevForId;
    ps = borrowPreparedStatement(sql);

    List<CertRevInfoWithSerial> ret = new ArrayList<>();
    for (Long id : ids) {
      try {
        ps.setLong(1, id);
        rs = ps.executeQuery();

        if (!rs.next()) {
          continue;
        }

        int ee = rs.getInt("EE");
        if (onlyCaCerts) {
          if (ee != 0) {
            continue;
          }
        } else if (onlyUserCerts) {
          if (ee != 1) {
            continue;
          }
        }

        CertRevInfoWithSerial revInfo;

        String serial = rs.getString("SN");
        boolean revoked = rs.getBoolean("REVOEKD");
        if (revoked) {
          long revInvTime = rs.getLong("RIT");
          Date invalidityTime = (revInvTime == 0) ? null : new Date(1000 * revInvTime);
          revInfo = new CertRevInfoWithSerial(id, new BigInteger(serial, 16), rs.getInt("RR"),
              new Date(1000 * rs.getLong("RT")), invalidityTime);
        } else {
          revInfo = new CertRevInfoWithSerial(id, new BigInteger(serial, 16),
              CrlReason.REMOVE_FROM_CRL.getCode(), new Date(1000 * rs.getLong("LUPDATE")), null);
        }
        ret.add(revInfo);
      } catch (SQLException ex) {
        throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
      } finally {
        releaseDbResources(null, rs);
      }
    } // end for

    return ret;
  } // method getCertificatesForDeltaCrl

  public CertStatus getCertStatusForSubject(NameId ca, X500Name subject) throws OperationException {
    long subjectFp = X509Util.fpCanonicalizedName(subject);
    return getCertStatusForSubjectFp(ca, subjectFp);
  }

  private CertStatus getCertStatusForSubjectFp(NameId ca, long subjectFp)
      throws OperationException {
    ParamUtil.requireNonNull("ca", ca);

    final String sql = sqls.sqlCertStatusForSubjectFp;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setLong(1, subjectFp);
      ps.setInt(2, ca.getId());
      rs = ps.executeQuery();
      if (!rs.next()) {
        return CertStatus.UNKNOWN;
      }
      return rs.getBoolean("REV") ? CertStatus.REVOKED : CertStatus.GOOD;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getCertStatusForSubjectFp

  public boolean isCertForSubjectIssued(NameId ca, long subjectFp) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    String sql = sqls.sqlCertforSubjectIssued;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());
      ps.setLong(2, subjectFp);
      rs = ps.executeQuery();
      return rs.next();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  public boolean isCertForKeyIssued(NameId ca, long keyFp) throws OperationException {
    ParamUtil.requireNonNull("ca", ca);
    String sql = sqls.sqlCertForKeyIssued;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, ca.getId());
      ps.setLong(2, keyFp);
      rs = ps.executeQuery();
      return rs.next();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  }

  private String base64Fp(byte[] data) {
    return HashAlgo.SHA1.base64Hash(data);
  }

  private PreparedStatement[] borrowPreparedStatements(String... sqlQueries)
      throws OperationException {
    Connection conn;
    try {
      conn = datasource.getConnection();
    } catch (DataAccessException ex) {
      throw new OperationException(DB_FAILURE, ex.getMessage());
    }

    if (conn == null) {
      throw new OperationException(DB_FAILURE, "could not get connection");
    }

    final int n = sqlQueries.length;
    PreparedStatement[] pss = new PreparedStatement[n];
    for (int i = 0; i < n; i++) {
      try {
        pss[i] = datasource.prepareStatement(conn, sqlQueries[i]);
      } catch (DataAccessException ex) {
        throw new OperationException(DB_FAILURE, ex.getMessage());
      }
      if (pss[i] != null) {
        continue;
      }

      // destroy all already initialized statements
      for (int j = 0; j < i; j++) {
        try {
          pss[j].close();
        } catch (Throwable th) {
          LOG.warn("could not close preparedStatement", th);
        }
      }

      try {
        conn.close();
      } catch (Throwable th) {
        LOG.warn("could not close connection", th);
      }

      throw new OperationException(DB_FAILURE,
          "could not create prepared statement for " + sqlQueries[i]);
    }

    return pss;
  } // method borrowPreparedStatements

  private PreparedStatement borrowPreparedStatement(String sqlQuery)
      throws OperationException {
    PreparedStatement ps = null;
    try {
      Connection conn = datasource.getConnection();
      if (conn != null) {
        ps = datasource.prepareStatement(conn, sqlQuery);
      }
    } catch (DataAccessException ex) {
      LOG.debug("DataAccessException", ex);
      throw new OperationException(DB_FAILURE, ex.getMessage());
    }

    if (ps != null) {
      return ps;
    }

    throw new OperationException(DB_FAILURE, "could not create prepared statement for " + sqlQuery);
  } // method borrowPreparedStatement

  private void releaseDbResources(Statement ps, ResultSet rs) {
    datasource.releaseResources(ps, rs);
  }

  public boolean isHealthy() {
    final String sql = "SELECT ID FROM CA";

    try {
      PreparedStatement ps = borrowPreparedStatement(sql);

      ResultSet rs = null;
      try {
        rs = ps.executeQuery();
      } finally {
        releaseDbResources(ps, rs);
      }
      return true;
    } catch (Exception ex) {
      LOG.error("isHealthy(). {}: {}", ex.getClass().getName(), ex.getMessage());
      LOG.debug("isHealthy()", ex);
      return false;
    }
  } // method isHealthy

  public String getLatestSerialNumber(X500Name nameWithSn) throws OperationException {
    RDN[] rdns1 = nameWithSn.getRDNs();
    RDN[] rdns2 = new RDN[rdns1.length];
    for (int i = 0; i < rdns1.length; i++) {
      RDN rdn = rdns1[i];
      rdns2[i] =  rdn.getFirst().getType().equals(ObjectIdentifiers.DN_SERIALNUMBER)
          ? new RDN(ObjectIdentifiers.DN_SERIALNUMBER, new DERPrintableString("%")) : rdn;
    }

    String namePattern = X509Util.getRfc4519Name(new X500Name(rdns2));

    final String sql = sqls.sqlLatestSerialForSubjectLike;
    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    String subjectStr;

    try {
      ps.setString(1, namePattern);
      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }

      subjectStr = rs.getString("SUBJECT");
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, ex.getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }

    X500Name lastName = new X500Name(subjectStr);
    RDN[] rdns = lastName.getRDNs(ObjectIdentifiers.DN_SERIALNUMBER);
    if (rdns == null || rdns.length == 0) {
      return null;
    }

    return X509Util.rdnValueToString(rdns[0].getFirst().getValue());
  } // method getLatestSerialNumber

  public Long getNotBeforeOfFirstCertStartsWithCommonName(String commonName, NameId profile)
      throws OperationException {
    final String sql = sqls.sqlLatestSerialForCertprofileAndSubjectLike;

    ResultSet rs = null;
    PreparedStatement ps = borrowPreparedStatement(sql);

    try {
      ps.setInt(1, profile.getId());
      ps.setString(2, "%cn=" + commonName + "%");

      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }

      long notBefore = rs.getLong("NBEFORE");
      return (notBefore == 0) ? null : notBefore;
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, rs);
    }
  } // method getNotBeforeOfFirstCertStartsWithCommonName

  public void deleteUnreferencedRequests() throws OperationException {
    final String sql = SQLs.SQL_DELETE_UNREFERENCED_REQUEST;
    PreparedStatement ps = borrowPreparedStatement(sql);
    ResultSet rs = null;
    try {
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      datasource.releaseResources(ps, rs);
    }
  }

  public long addRequest(byte[] request) throws OperationException {
    ParamUtil.requireNonNull("request", request);

    long id = idGenerator.nextId();
    long currentTimeSeconds = System.currentTimeMillis() / 1000;
    String b64Request = Base64.encodeToString(request);
    final String sql = SQLs.SQL_ADD_REQUEST;
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setLong(1, id);
      ps.setLong(2, currentTimeSeconds);
      ps.setString(3, b64Request);
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }

    return id;
  }

  public void addRequestCert(long requestId, long certId) throws OperationException {
    final String sql = SQLs.SQL_ADD_REQCERT;
    long id = idGenerator.nextId();
    PreparedStatement ps = borrowPreparedStatement(sql);
    try {
      ps.setLong(1, id);
      ps.setLong(2, requestId);
      ps.setLong(3, certId);
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new OperationException(DB_FAILURE, datasource.translate(sql, ex).getMessage());
    } finally {
      releaseDbResources(ps, null);
    }
  }

  private static void releaseStatement(Statement statment) {
    if (statment == null) {
      return;
    }
    try {
      statment.close();
    } catch (SQLException ex) {
      LOG.warn("could not close Statement", ex);
    }
  }

  private static void setBoolean(PreparedStatement ps, int index, boolean value)
      throws SQLException {
    ps.setInt(index, value ? 1 : 0);
  }

  private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value != null) {
      ps.setLong(index, value.longValue());
    } else {
      ps.setNull(index, Types.BIGINT);
    }
  }

  private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value != null) {
      ps.setInt(index, value.intValue());
    } else {
      ps.setNull(index, Types.INTEGER);
    }
  }

  private static void setDateSeconds(PreparedStatement ps, int index, Date date)
      throws SQLException {
    if (date != null) {
      ps.setLong(index, date.getTime() / 1000);
    } else {
      ps.setNull(index, Types.BIGINT);
    }
  }

}
