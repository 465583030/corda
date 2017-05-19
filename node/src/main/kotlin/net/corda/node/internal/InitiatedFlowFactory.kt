package net.corda.node.internal

import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.node.services.statemachine.SessionInit

interface InitiatedFlowFactory {
    fun createFlow(platformVersion: Int, otherParty: Party, sessionInit: SessionInit): FlowLogic<*>

    data class Core(val factory: (Party, Int) -> FlowLogic<*>) : InitiatedFlowFactory {
        override fun createFlow(platformVersion: Int, otherParty: Party, sessionInit: SessionInit): FlowLogic<*> {
            return factory(otherParty, platformVersion)
        }
    }

    data class CorDapp(val version: Int, val factory: (Party) -> FlowLogic<*>) : InitiatedFlowFactory {
        override fun createFlow(platformVersion: Int, otherParty: Party, sessionInit: SessionInit): FlowLogic<*> {
            // TODO Add support for multiple versions of the same flow when CorDapps are loaded in separate class loaders
            if (sessionInit.flowVerison == version) return factory(otherParty)
            throw SessionRejectException(
                    "Version not supported",
                    "Version mismatch - ${sessionInit.initiatingFlowClass} is only registered for version $version")
        }
    }
}

class SessionRejectException(val rejectMessage: String, val logMessage: String) : Exception()
