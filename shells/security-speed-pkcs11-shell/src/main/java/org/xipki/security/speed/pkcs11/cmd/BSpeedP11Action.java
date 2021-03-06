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

package org.xipki.security.speed.pkcs11.cmd;

import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.xipki.console.karaf.IllegalCmdParamException;
import org.xipki.security.exception.XiSecurityException;
import org.xipki.security.pkcs11.P11CryptService;
import org.xipki.security.pkcs11.P11CryptServiceFactory;
import org.xipki.security.pkcs11.P11Module;
import org.xipki.security.pkcs11.P11Slot;
import org.xipki.security.pkcs11.P11SlotIdentifier;
import org.xipki.security.pkcs11.exception.P11TokenException;
import org.xipki.security.speed.cmd.BatchSpeedAction;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public abstract class BSpeedP11Action extends BatchSpeedAction {

  @Reference (optional = true)
  protected P11CryptServiceFactory p11CryptServiceFactory;

  @Option(name = "--slot", required = true,
      description = "slot index\n(required)")
  protected Integer slotIndex;

  @Option(name = "--module",
      description = "name of the PKCS#11 module.")
  @Completion(P11ModuleNameCompleter.class)
  protected String moduleName = P11CryptServiceFactory.DEFAULT_P11MODULE_NAME;

;

  protected P11Slot getSlot()
      throws XiSecurityException, P11TokenException, IllegalCmdParamException {
    P11CryptService p11Service = p11CryptServiceFactory.getP11CryptService(moduleName);
    if (p11Service == null) {
      throw new IllegalCmdParamException("undefined module " + moduleName);
    }
    P11Module module = p11Service.getModule();
    P11SlotIdentifier slotId = module.getSlotIdForIndex(slotIndex);
    return module.getSlot(slotId);
  }

}
