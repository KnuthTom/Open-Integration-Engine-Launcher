/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Pins a publisher by **DN attributes** (e.g. `O="NEXTGEN HEALTHCARE, INC."`) rather than by the
 * leaf certificate's serial number — so when the publisher renews its code-signing certificate (new
 * serial, new validity, possibly new CN) the pin still matches as long as the organizational identity
 * is unchanged. All required attributes must be present on the leaf for a match.
 */
data class SignerPin(val label: String, val requiredRdns: Map<String, String>) {

    fun matches(leaf: X509Certificate): Boolean {
        val name = X500Name(leaf.subjectX500Principal.getName(X500Principal.RFC2253))
        return requiredRdns.all { (attr, expected) ->
            val oid = BCStyle.INSTANCE.attrNameToOID(attr)
            val present = name.getRDNs(oid)
                .flatMap { it.typesAndValues.toList() }
                .map { plainValue(it.value).trim() }
            present.any { it.equals(expected.trim(), ignoreCase = true) }
        }
    }

    companion object {
        fun of(label: String, vararg rdns: Pair<String, String>): SignerPin = SignerPin(label, rdns.toMap())

        /**
         * The plain, UNESCAPED string of a DN attribute value. [IETFUtils.valueToString] renders an RFC-2253
         * *representation*, which escapes special characters — so an organization value like
         * `Nextgen Healthcare, Inc.` comes back as `Nextgen Healthcare\, Inc.` and no longer equals the
         * pin's `NEXTGEN HEALTHCARE, INC.`. We compare against the actual value instead (publishers whose
         * O/CN contains a comma are common), falling back to [IETFUtils.valueToString] for the rare
         * non-string ASN.1 value. This only ever fixes false *negatives* — matching still requires exact
         * (case-insensitive) equality of the real attribute value.
         */
        private fun plainValue(value: ASN1Encodable): String =
            (value as? ASN1String)?.string ?: IETFUtils.valueToString(value)
    }
}
