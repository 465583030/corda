package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.Crypto
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.bouncycastle.cert.X509CertificateHolder
import java.security.cert.CertPath

/**
 * Very basic flow which exchanges transaction key and certificate paths between two parties in a transaction.
 * This is intended for use as a subflow of another flow.
 */
object TxKeyFlow {
    abstract class AbstractIdentityFlow(val otherSide: PartyAndCertificate, val revocationEnabled: Boolean): FlowLogic<Map<Party, AnonymousIdentity>>() {
        fun validateIdentity(untrustedIdentity: Pair<X509CertificateHolder, CertPath>): AnonymousIdentity {
            val (theirCert, certPath) = untrustedIdentity
            if (theirCert.subject == otherSide.name) {
                val anonymousParty = AnonymousParty(Crypto.decodePublicKey(theirCert.subjectPublicKeyInfo.encoded))
                serviceHub.identityService.registerPath(otherSide.certificate, anonymousParty, certPath)
                return AnonymousIdentity(certPath, theirCert, anonymousParty)
            } else
                throw IllegalStateException("Expected certificate subject to be ${otherSide.name} but found ${theirCert.subject}")
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class Requester(otherSide: PartyAndCertificate,
                    revocationEnabled: Boolean,
                    override val progressTracker: ProgressTracker) : AbstractIdentityFlow(otherSide, revocationEnabled) {
        constructor(otherSide: PartyAndCertificate,
                    revocationEnabled: Boolean) : this(otherSide, revocationEnabled, tracker())

        companion object {
            object AWAITING_KEY : ProgressTracker.Step("Awaiting key")

            fun tracker() = ProgressTracker(AWAITING_KEY)
        }

        @Suspendable
        override fun call(): Map<Party, AnonymousIdentity> {
            progressTracker.currentStep = AWAITING_KEY
            val myIdentityFragment = serviceHub.keyManagementService.freshKeyAndCert(serviceHub.myInfo.legalIdentity, revocationEnabled)
            val theirIdentity = receive<Pair<X509CertificateHolder, CertPath>>(otherSide).unwrap { validateIdentity(it) }
            send(otherSide, myIdentityFragment)
            return mapOf(Pair(otherSide, AnonymousIdentity(myIdentityFragment)),
                    Pair(serviceHub.myInfo.legalIdentity, theirIdentity))
        }
    }

    /**
     * Flow which waits for a key request from a counterparty, generates a new key and then returns it to the
     * counterparty and as the result from the flow.
     */
    class Provider(otherSide: PartyAndCertificate,
                   revocationEnabled: Boolean,
                   override val progressTracker: ProgressTracker) : AbstractIdentityFlow(otherSide,revocationEnabled) {
        constructor(otherSide: PartyAndCertificate,
                    revocationEnabled: Boolean = false) : this(otherSide, revocationEnabled, tracker())

        companion object {
            object SENDING_KEY : ProgressTracker.Step("Sending key")

            fun tracker() = ProgressTracker(SENDING_KEY)
        }

        @Suspendable
        override fun call(): Map<Party, AnonymousIdentity> {
            progressTracker.currentStep = SENDING_KEY
            val myIdentityFragment = serviceHub.keyManagementService.freshKeyAndCert(serviceHub.myInfo.legalIdentity, revocationEnabled)
            send(otherSide, myIdentityFragment)
            val theirIdentity = receive<Pair<X509CertificateHolder, CertPath>>(otherSide).unwrap { validateIdentity(it) }
            return mapOf(Pair(otherSide, AnonymousIdentity(myIdentityFragment)),
                    Pair(serviceHub.myInfo.legalIdentity, theirIdentity))
        }
    }

    data class AnonymousIdentity(
            val certPath: CertPath,
            val certificate: X509CertificateHolder,
            val identity: AnonymousParty) {
        constructor(myIdentity: Pair<X509CertificateHolder, CertPath>) : this(myIdentity.second,
                myIdentity.first,
                AnonymousParty(myIdentity.second.certificates.last().publicKey))
    }
}
