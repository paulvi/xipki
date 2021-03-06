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

package org.xipki.ca.qa.shell;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.xipki.ca.server.mgmt.api.ChangeCrlSignerEntry;
import org.xipki.ca.server.mgmt.api.CrlControl;
import org.xipki.ca.server.mgmt.api.CrlSignerEntry;
import org.xipki.ca.server.mgmt.shell.CrlSignerUpdateCmd;
import org.xipki.console.karaf.CmdFailure;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

@Command(scope = "caqa", name = "crlsigner-check",
    description = "check information of CRL signers (QA)")
@Service
public class CrlSignerCheckCmd extends CrlSignerUpdateCmd {

  @Override
  protected Object execute0() throws Exception {
    ChangeCrlSignerEntry ey = getCrlSignerChangeEntry();
    String name = ey.getName();
    println("checking CRL signer " + name);

    CrlSignerEntry cs = caManager.getCrlSigner(name);
    if (cs == null) {
      throw new CmdFailure("CRL signer named '" + name + "' is not configured");
    }

    if (ey.getSignerType() != null) {
      MgmtQaShellUtil.assertTypeEquals("signer type", ey.getSignerType(), cs.getType());
    }

    if (ey.getSignerConf() != null) {
      MgmtQaShellUtil.assertEquals("signer conf", ey.getSignerConf(), cs.getConf());
    }

    if (ey.getCrlControl() != null) {
      CrlControl ex = new CrlControl(ey.getCrlControl());
      CrlControl is = new CrlControl(cs.getCrlControl());

      if (!ex.equals(is)) {
        throw new CmdFailure("CRL control: is '" + is.getConf() + "', but expected '"
            + ex.getConf() + "'");
      }
    }

    if (ey.getBase64Cert() != null) {
      MgmtQaShellUtil.assertEquals("certificate", ey.getBase64Cert(), cs.getBase64Cert());
    }

    println(" checked CRL signer " + name);
    return null;
  } // method execute0

}
