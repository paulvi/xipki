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

package org.xipki.p11proxy.msg;

import java.io.IOException;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERUTF8String;
import org.xipki.common.util.ParamUtil;
import org.xipki.security.exception.BadAsn1ObjectException;
import org.xipki.security.pkcs11.P11NewKeyControl;
import org.xipki.security.pkcs11.P11SlotIdentifier;

/**
 * TODO.
 * <pre>
 * GenSM2KeypairParams ::= SEQUENCE {
 *     slotId               P11SlotIdentifier,
 *     label                UTF8STRING,
 *     control              NewKeyControl }
 * </pre>
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

// CHECKSTYLE:SKIP
public class Asn1GenSM2KeypairParams extends ASN1Object {

  private final P11SlotIdentifier slotId;

  private final String label;

  private final P11NewKeyControl control;

  public Asn1GenSM2KeypairParams(P11SlotIdentifier slotId, String label, P11NewKeyControl control) {
    this.slotId = ParamUtil.requireNonNull("slotId", slotId);
    this.label = ParamUtil.requireNonBlank("label", label);
    this.control = ParamUtil.requireNonNull("control", control);
  }

  private Asn1GenSM2KeypairParams(ASN1Sequence seq) throws BadAsn1ObjectException {
    Asn1Util.requireRange(seq, 3, 3);
    int idx = 0;
    slotId = Asn1P11SlotIdentifier.getInstance(seq.getObjectAt(idx++)).getSlotId();
    label = Asn1Util.getUtf8String(seq.getObjectAt(idx++));
    control = Asn1NewKeyControl.getInstance(seq.getObjectAt(idx++)).getControl();
  }

  public static Asn1GenSM2KeypairParams getInstance(Object obj) throws BadAsn1ObjectException {
    if (obj == null || obj instanceof Asn1GenSM2KeypairParams) {
      return (Asn1GenSM2KeypairParams) obj;
    }

    try {
      if (obj instanceof ASN1Sequence) {
        return new Asn1GenSM2KeypairParams((ASN1Sequence) obj);
      } else if (obj instanceof byte[]) {
        return getInstance(ASN1Primitive.fromByteArray((byte[]) obj));
      } else {
        throw new BadAsn1ObjectException("unknown object: " + obj.getClass().getName());
      }
    } catch (IOException | IllegalArgumentException ex) {
      throw new BadAsn1ObjectException("unable to parse encoded object: " + ex.getMessage(), ex);
    }
  }

  @Override
  public ASN1Primitive toASN1Primitive() {
    ASN1EncodableVector vector = new ASN1EncodableVector();
    vector.add(new Asn1P11SlotIdentifier(slotId));
    vector.add(new DERUTF8String(label));
    vector.add(new Asn1NewKeyControl(control));
    return new DERSequence(vector);
  }

  public P11SlotIdentifier getSlotId() {
    return slotId;
  }

  public String getLabel() {
    return label;
  }

  public P11NewKeyControl getControl() {
    return control;
  }

}
