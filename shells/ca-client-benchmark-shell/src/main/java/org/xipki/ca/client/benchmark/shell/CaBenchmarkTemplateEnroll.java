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

package org.xipki.ca.client.benchmark.shell;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.SchemaFactory;

import org.bouncycastle.asn1.crmf.CertRequest;
import org.bouncycastle.asn1.crmf.CertTemplate;
import org.bouncycastle.asn1.crmf.CertTemplateBuilder;
import org.bouncycastle.asn1.crmf.ProofOfPossession;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.client.api.CaClient;
import org.xipki.ca.client.api.CaClientException;
import org.xipki.ca.client.api.CertOrError;
import org.xipki.ca.client.api.EnrollCertResult;
import org.xipki.ca.client.api.PkiErrorException;
import org.xipki.ca.client.api.dto.EnrollCertRequest;
import org.xipki.ca.client.api.dto.EnrollCertRequest.Type;
import org.xipki.ca.client.api.dto.EnrollCertRequestEntry;
import org.xipki.ca.client.benchmark.shell.jaxb.EnrollCertType;
import org.xipki.ca.client.benchmark.shell.jaxb.EnrollTemplateType;
import org.xipki.ca.client.benchmark.shell.jaxb.ObjectFactory;
import org.xipki.common.InvalidConfException;
import org.xipki.common.BenchmarkExecutor;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.XmlUtil;
import org.xml.sax.SAXException;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CaBenchmarkTemplateEnroll extends BenchmarkExecutor {

  private static final class CertRequestWithProfile {

    private final String certprofile;

    private final CertRequest certRequest;

    CertRequestWithProfile(String certprofile, CertRequest certRequest) {
      this.certprofile = certprofile;
      this.certRequest = certRequest;
    }

  } // class CertRequestWithProfile

  class Testor implements Runnable {

    @Override
    public void run() {
      while (!stop() && getErrorAccout() < 1) {
        Map<Integer, CertRequestWithProfile> certReqs = nextCertRequests();
        if (certReqs == null) {
          break;
        }

        boolean successful = testNext(certReqs);
        int numFailed = successful ? 0 : 1;
        account(1, numFailed);
      }
    }

    private boolean testNext(Map<Integer, CertRequestWithProfile> certRequests) {
      EnrollCertResult result;
      try {
        EnrollCertRequest request = new EnrollCertRequest(Type.CERT_REQ);
        for (Integer certId : certRequests.keySet()) {
          CertRequestWithProfile certRequest = certRequests.get(certId);
          EnrollCertRequestEntry requestEntry = new EnrollCertRequestEntry("id-" + certId,
                  certRequest.certprofile, certRequest.certRequest, RA_VERIFIED);
          request.addRequestEntry(requestEntry);
        }

        result = caClient.requestCerts(null, request, null);
      } catch (CaClientException | PkiErrorException ex) {
        LOG.warn("{}: {}", ex.getClass().getName(), ex.getMessage());
        return false;
      } catch (Throwable th) {
        LOG.warn("{}: {}", th.getClass().getName(), th.getMessage());
        return false;
      }

      if (result == null) {
        return false;
      }

      Set<String> ids = result.getAllIds();
      if (ids.size() < certRequests.size()) {
        return false;
      }

      for (String id : ids) {
        CertOrError certOrError = result.getCertOrError(id);
        X509Certificate cert = (X509Certificate) certOrError.getCertificate();

        if (cert == null) {
          return false;
        }
      }

      return true;
    } // method testNext

  } // class Testor

  private static final Logger LOG = LoggerFactory.getLogger(CaBenchmarkTemplateEnroll.class);

  private static final ProofOfPossession RA_VERIFIED = new ProofOfPossession();

  private static Object jaxbUnmarshallerLock = new Object();

  private static Unmarshaller jaxbUnmarshaller;

  private final CaClient caClient;

  private final List<BenchmarkEntry> benchmarkEntries;

  private final int maxRequests;

  private AtomicInteger processedRequests = new AtomicInteger(0);

  private final AtomicLong index;

  public CaBenchmarkTemplateEnroll(CaClient caClient, EnrollTemplateType template,
      int maxRequests, String description) throws Exception {
    super(description);

    ParamUtil.requireNonNull("template", template);
    this.maxRequests = maxRequests;
    this.caClient = ParamUtil.requireNonNull("caClient", caClient);

    Calendar baseTime = Calendar.getInstance(Locale.UK);
    baseTime.set(Calendar.YEAR, 2014);
    baseTime.set(Calendar.MONTH, 0);
    baseTime.set(Calendar.DAY_OF_MONTH, 1);

    this.index = new AtomicLong(getSecureIndex());

    List<EnrollCertType> list = template.getEnrollCert();
    benchmarkEntries = new ArrayList<>(list.size());

    for (EnrollCertType entry : list) {
      KeyEntry keyEntry;
      if (entry.getEcKey() != null) {
        keyEntry = new KeyEntry.ECKeyEntry(entry.getEcKey().getCurve());
      } else if (entry.getRsaKey() != null) {
        keyEntry = new KeyEntry.RSAKeyEntry(entry.getRsaKey().getModulusLength());
      } else if (entry.getDsaKey() != null) {
        keyEntry = new KeyEntry.DSAKeyEntry(entry.getDsaKey().getPLength());
      } else {
        throw new RuntimeException("should not reach here, unknown child of KeyEntry");
      }

      String randomDnStr = entry.getRandomDN();
      BenchmarkEntry.RandomDn randomDn = BenchmarkEntry.RandomDn.getInstance(randomDnStr);
      if (randomDn == null) {
        throw new InvalidConfException("invalid randomDN " + randomDnStr);
      }

      benchmarkEntries.add(
          new BenchmarkEntry(entry.getCertprofile(), keyEntry, entry.getSubject(), randomDn));
    }
  } // constructor

  @Override
  protected Runnable getTestor() throws Exception {
    return new Testor();
  }

  public int getNumberOfCertsInOneRequest() {
    return benchmarkEntries.size();
  }

  private Map<Integer, CertRequestWithProfile> nextCertRequests() {
    if (maxRequests > 0) {
      int num = processedRequests.getAndAdd(1);
      if (num >= maxRequests) {
        return null;
      }
    }

    Map<Integer, CertRequestWithProfile> certRequests = new HashMap<>();
    final int n = benchmarkEntries.size();
    for (int i = 0; i < n; i++) {
      BenchmarkEntry benchmarkEntry = benchmarkEntries.get(i);
      final int certId = i + 1;
      CertTemplateBuilder certTempBuilder = new CertTemplateBuilder();

      long thisIndex = index.getAndIncrement();
      certTempBuilder.setSubject(benchmarkEntry.getX500Name(thisIndex));

      SubjectPublicKeyInfo spki = benchmarkEntry.getSubjectPublicKeyInfo();
      certTempBuilder.setPublicKey(spki);

      CertTemplate certTemplate = certTempBuilder.build();
      CertRequest certRequest = new CertRequest(certId, certTemplate, null);
      CertRequestWithProfile requestWithCertprofile = new CertRequestWithProfile(
              benchmarkEntry.getCertprofile(), certRequest);
      certRequests.put(certId, requestWithCertprofile);
    }
    return certRequests;
  } // method nextCertRequests

  public static EnrollTemplateType parse(InputStream configStream) throws InvalidConfException {
    ParamUtil.requireNonNull("configStream", configStream);
    Object root;

    synchronized (jaxbUnmarshallerLock) {
      try {
        if (jaxbUnmarshaller == null) {
          JAXBContext context = JAXBContext.newInstance(ObjectFactory.class);
          jaxbUnmarshaller = context.createUnmarshaller();

          final SchemaFactory schemaFact = SchemaFactory.newInstance(
              javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
          URL url = ObjectFactory.class.getResource("/xsd/benchmark.xsd");
          jaxbUnmarshaller.setSchema(schemaFact.newSchema(url));
        }

        root = jaxbUnmarshaller.unmarshal(configStream);
      } catch (SAXException ex) {
        throw new InvalidConfException("parsing profile failed, message: " + ex.getMessage(), ex);
      } catch (JAXBException ex) {
        throw new InvalidConfException("parsing profile failed, message: "
            + XmlUtil.getMessage(ex), ex);
      }
    }

    try {
      configStream.close();
    } catch (IOException ex) {
      LOG.warn("could not close xmlConfStream: {}", ex.getMessage());
    }

    if (root instanceof JAXBElement) {
      return (EnrollTemplateType) ((JAXBElement<?>) root).getValue();
    } else {
      throw new InvalidConfException("invalid root element type");
    }
  } // method parse

}
