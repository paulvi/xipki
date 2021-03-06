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

package org.xipki.security.shell.pkcs12;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.xipki.console.karaf.IllegalCmdParamException;
import org.xipki.security.pkcs12.P12KeyGenerationResult;
import org.xipki.security.pkcs12.P12KeyGenerator;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

@Command(scope = "xi", name = "dsa-p12",
    description = "generate RSA keypair in PKCS#12 keystore")
@Service
// CHECKSTYLE:SKIP
public class P12DSAKeyGenCmd extends P12KeyGenAction {

  @Option(name = "--subject", aliases = "-s",
      description = "subject of the self-signed certificate")
  private String subject;

  @Option(name = "--plen",
      description = "bit length of the prime")
  private Integer plen = 2048;

  @Option(name = "--qlen",
      description = "bit length of the sub-prime")
  private Integer qlen;

  @Override
  protected Object execute0() throws Exception {
    if (plen % 1024 != 0) {
      throw new IllegalCmdParamException("plen is not multiple of 1024: " + plen);
    }

    if (qlen == null) {
      if (plen <= 1024) {
        qlen = 160;
      } else if (plen <= 2048) {
        qlen = 224;
      } else {
        qlen = 256;
      }
    }

    P12KeyGenerationResult keypair = new P12KeyGenerator().generateDSAKeypair(plen,
        qlen, getKeyGenParameters(), subject);
    saveKey(keypair);

    return null;
  }

}
