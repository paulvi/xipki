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
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.xipki.security.pkcs12.P12KeyGenerationResult;
import org.xipki.security.pkcs12.P12KeyGenerator;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

@Command(scope = "xi", name = "sm2-p12",
    description = "generate SM2 (curve sm2p256v1) keypair in PKCS#12 keystore")
@Service
// CHECKSTYLE:SKIP
public class P12SM2KeyGenCmd extends P12KeyGenAction {

  @Option(name = "--subject", aliases = "-s",
      description = "subject of the self-signed certificate")
  protected String subject;

  @Override
  protected Object execute0() throws Exception {
    P12KeyGenerationResult keypair = new P12KeyGenerator().generateECKeypair(
        GMObjectIdentifiers.sm2p256v1.getId(), getKeyGenParameters(), subject);
    saveKey(keypair);

    return null;
  }

}
