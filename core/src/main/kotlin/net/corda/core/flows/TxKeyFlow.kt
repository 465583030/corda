package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.CertificateType
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.X509Utilities
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.bouncycastle.asn1.x500.X500Name
import java.security.cert.CertPath
import java.security.cert.X509Certificate

/**
 * Very basic flow which exchanges transaction key and certificate paths between two parties in a transaction.
 * This is intended for use as a subflow of another flow.
 */
object TxKeyFlow {
    abstract class AbstractIdentityFlow(val otherSide: Party, val revocationEnabled: Boolean): FlowLogic<Map<Party, AnonymousIdentity>>() {
        /**
         * Generate a new key and corresponding certificate, store the certificate path locally so we
         * have a copy
         */
        fun generateIdentity(): Pair<X509Certificate, CertPath> {
            val ourPublicKey = serviceHub.keyManagementService.freshKey()
            val ourParty = Party(serviceHub.myInfo.legalIdentity.name, ourPublicKey)
            // FIXME: Use the actual certificate for the identity the flow is presenting themselves as
            // FIXME: Generate EdDSA keys and non-TLS certs
            val issuerKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
            val issuerCertificate = X509Utilities.createSelfSignedCACertificate(ourParty.name, issuerKey)
            val ourCertificate = X509Utilities.createCertificate(CertificateType.TLS, issuerCertificate, issuerKey, ourParty.name, ourPublicKey)
            val ourCertPath = X509Utilities.createCertificatePath(issuerCertificate, ourCertificate, revocationEnabled = revocationEnabled)
            serviceHub.identityService.registerPath(issuerCertificate,
                    AnonymousParty(ourParty.owningKey),
                    ourCertPath)
            return Pair(issuerCertificate, ourCertPath)
        }

        fun validateIdentity(untrustedIdentity: Pair<X509Certificate, CertPath>): AnonymousIdentity {
            val (wellKnownCert, certPath) = untrustedIdentity
            val theirCert = certPath.certificates.last()
            // TODO: Don't trust self-signed certificates
            return if (theirCert is X509Certificate) {
                val certName = X500Name(theirCert.subjectDN.name)
                if (certName == otherSide.name) {
                    val anonymousParty = AnonymousParty(theirCert.publicKey)
                    serviceHub.identityService.registerPath(wellKnownCert, anonymousParty, certPath)
                    AnonymousIdentity(certPath, theirCert, anonymousParty)
                } else
                    throw IllegalStateException("Expected certificate subject to be ${otherSide.name} but found ${certName}")
            } else
                throw IllegalStateException("Expected an X.509 certificate but received ${theirCert.javaClass.name}")
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class Requester(otherSide: Party,
                    revocationEnabled: Boolean,
                    override val progressTracker: ProgressTracker) : AbstractIdentityFlow(otherSide, revocationEnabled) {
        constructor(otherSide: Party,
                    revocationEnabled: Boolean) : this(otherSide, revocationEnabled, tracker())

        companion object {
            object AWAITING_KEY : ProgressTracker.Step("Awaiting key")

            fun tracker() = ProgressTracker(AWAITING_KEY)
        }

        @Suspendable
        override fun call(): Map<Party, AnonymousIdentity> {
            progressTracker.currentStep = AWAITING_KEY
            val myIdentityFragment = generateIdentity()
            val theirIdentity = receive<Pair<X509Certificate, CertPath>>(otherSide).unwrap { validateIdentity(it) }
            send(otherSide, myIdentityFragment)
            return mapOf(Pair(otherSide, AnonymousIdentity(myIdentityFragment)),
                    Pair(serviceHub.myInfo.legalIdentity, theirIdentity))
        }
    }

    /**
     * Flow which waits for a key request from a counterparty, generates a new key and then returns it to the
     * counterparty and as the result from the flow.
     */
    class Provider(otherSide: Party,
                   revocationEnabled: Boolean,
                   override val progressTracker: ProgressTracker) : AbstractIdentityFlow(otherSide,revocationEnabled) {
        constructor(otherSide: Party,
                    revocationEnabled: Boolean = false) : this(otherSide, revocationEnabled, tracker())

        companion object {
            object SENDING_KEY : ProgressTracker.Step("Sending key")

            fun tracker() = ProgressTracker(SENDING_KEY)
        }

        @Suspendable
        override fun call(): Map<Party, AnonymousIdentity> {
            progressTracker.currentStep = SENDING_KEY
            val myIdentityFragment = generateIdentity()
            send(otherSide, myIdentityFragment)
            val theirIdentity = receive<Pair<X509Certificate, CertPath>>(otherSide).unwrap { validateIdentity(it) }
            return mapOf(Pair(otherSide, AnonymousIdentity(myIdentityFragment)),
                    Pair(serviceHub.myInfo.legalIdentity, theirIdentity))
        }
    }

    data class AnonymousIdentity(
            val certPath: CertPath,
            val certificate: X509Certificate,
            val identity: AnonymousParty) {
        constructor(myIdentity: Pair<X509Certificate, CertPath>) : this(myIdentity.second,
                myIdentity.first,
                AnonymousParty(myIdentity.second.certificates.last().publicKey))
    }
}
