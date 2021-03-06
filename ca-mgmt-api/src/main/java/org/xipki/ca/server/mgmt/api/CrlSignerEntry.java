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

package org.xipki.ca.server.mgmt.api;

import java.security.cert.X509Certificate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.common.InvalidConfException;
import org.xipki.common.util.CompareUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.security.SignerConf;
import org.xipki.security.util.X509Util;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CrlSignerEntry {

  private static final Logger LOG = LoggerFactory.getLogger(CrlSignerEntry.class);

  private final String name;

  private final String signerType;

  private final String base64Cert;

  private String signerConf;

  private X509Certificate cert;

  private boolean certFaulty;

  private boolean confFaulty;

  private String crlControl;

  public CrlSignerEntry(String name, String signerType, String signerConf,
      String base64Cert, String crlControl) throws InvalidConfException {
    this.name = ParamUtil.requireNonBlank("name", name).toLowerCase();
    this.signerType = ParamUtil.requireNonBlank("signerType", signerType);
    this.signerConf = signerConf;
    this.crlControl = ParamUtil.requireNonNull("crlControl", crlControl);

    this.base64Cert = "CA".equalsIgnoreCase(name) ? null : base64Cert;

    if (this.base64Cert != null) {
      try {
        this.cert = X509Util.parseBase64EncodedCert(base64Cert);
      } catch (Throwable th) {
        LOG.debug("could not parse the certificate of CRL signer '" + name + "'");
        certFaulty = true;
      }
    }
  }

  public String getName() {
    return name;
  }

  public void setConfFaulty(boolean faulty) {
    this.confFaulty = faulty;
  }

  public void setConf(String conf) {
    this.signerConf = conf;
  }

  public boolean isFaulty() {
    return certFaulty || confFaulty;
  }

  public String getType() {
    return signerType;
  }

  public String getConf() {
    return signerConf;
  }

  public String getBase64Cert() {
    return base64Cert;
  }

  public X509Certificate getCert() {
    return cert;
  }

  public void setCert(X509Certificate cert) {
    if (base64Cert != null) {
      throw new IllegalStateException("certificate is already by specified by base64Cert");
    }
    this.cert = cert;
  }

  public String getCrlControl() {
    return crlControl;
  }

  @Override
  public String toString() {
    return toString(false);
  }

  public String toString(boolean verbose) {
    return toString(verbose, true);
  }

  public String toString(boolean verbose, boolean ignoreSensitiveInfo) {
    return StringUtil.concatObjectsCap(1000, "name: ", name, "\nfaulty: ", isFaulty(),
        "\nsignerType: ", signerType,
        "\nsignerConf: ", (signerConf == null ? "null"
            : SignerConf.toString(signerConf, verbose, ignoreSensitiveInfo)),
        "\ncrlControl: ", crlControl,
        "\ncert:\n", InternUtil.formatCert(cert, verbose));
  } // method toString

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof CrlSignerEntry)) {
      return false;
    }

    CrlSignerEntry objB = (CrlSignerEntry) obj;
    if (!name.equals(objB.name)) {
      return false;
    }

    if (!signerType.equals(objB.signerType)) {
      return false;
    }

    if (!CompareUtil.equalsObject(signerConf, signerConf)) {
      return false;
    }

    if (!CompareUtil.equalsObject(crlControl, objB.crlControl)) {
      return false;
    }

    if (!CompareUtil.equalsObject(base64Cert, objB.base64Cert)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

}
