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

package org.xipki.security.pkcs11.proxy.msg;

import java.io.IOException;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERTaggedObject;
import org.xipki.security.exception.BadAsn1ObjectException;

/**
 *
 * <pre>
 * ASN1P11Params ::= CHOICE {
 *     rsaPkcsPssParams   [0]  RSA-PKCS-PSS-Parameters
 *     opaqueParams       [1]  OCTET-STRING }
 * </pre>
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class Asn1P11Params extends ASN1Object {

    private final int tagNo;
    private final ASN1Encodable p11Params;

    public Asn1P11Params(ASN1Encodable p11Params) {
        if (p11Params instanceof Asn1RSAPkcsPssParams) {
            this.tagNo = 0;
        } else if (p11Params instanceof ASN1OctetString) {
            this.tagNo = 1;
        } else {
            throw new IllegalArgumentException("Illegal p11Params");
        }
        this.p11Params = p11Params;
    }

    private Asn1P11Params(ASN1TaggedObject taggedObject) throws BadAsn1ObjectException {
        this.tagNo = taggedObject.getTagNo();
        if (tagNo == 0) {
            this.p11Params = Asn1RSAPkcsPssParams.getInstance(taggedObject.getObject());
        } else if (tagNo == 1) {
            this.p11Params = DEROctetString.getInstance(taggedObject.getObject());
        } else {
            throw new BadAsn1ObjectException("invalid tag " + tagNo);
        }
    }

    public static Asn1P11Params getInstance(Object obj) throws BadAsn1ObjectException {
        if (obj == null || obj instanceof Asn1P11Params) {
            return (Asn1P11Params) obj;
        }

        try {
            if (obj instanceof ASN1TaggedObject) {
                return new Asn1P11Params((ASN1TaggedObject) obj);
            } else if (obj instanceof byte[]) {
                return getInstance(ASN1Primitive.fromByteArray((byte[]) obj));
            } else {
                throw new BadAsn1ObjectException("unknown object: " + obj.getClass().getName());
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new BadAsn1ObjectException("unable to parse encoded object: " + ex.getMessage(),
                    ex);
        }
    }

    @Override
    public ASN1Primitive toASN1Primitive() {
        return new DERTaggedObject(tagNo, p11Params);
    }

    public ASN1Encodable p11Params() {
        return p11Params;
    }

}
