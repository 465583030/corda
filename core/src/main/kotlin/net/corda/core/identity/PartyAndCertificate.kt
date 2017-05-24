package net.corda.core.identity

import org.bouncycastle.asn1.x500.X500Name
import java.security.PublicKey
import java.security.cert.CertPath
import java.security.cert.X509Certificate

/**
 * A full party plus the X.509 certificate and path linking the party back to a trust root.
 */
class PartyAndCertificate(name: X500Name, owningKey: PublicKey,
                          val certificate: X509Certificate,
                          val path: CertPath) : Party(name, owningKey)