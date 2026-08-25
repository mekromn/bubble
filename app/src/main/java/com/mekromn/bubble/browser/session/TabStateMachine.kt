package com.mekromn.bubble.browser.session

sealed interface TabEvent {
    data class PresentAs(val state: PresentationState) : TabEvent
    data class SetResidency(val state: ResidencyState) : TabEvent
    data object RendererGone : TabEvent
    data object RendererRecovered : TabEvent
    data class SetPinned(val pinned: Boolean) : TabEvent
}

class IllegalTabTransition(message: String) : IllegalStateException(message)

object TabStateMachine {
    private val legalResidencyTransitions: Map<ResidencyState, Set<ResidencyState>> = mapOf(
        ResidencyState.ACTIVE to setOf(
            ResidencyState.WARM,
            ResidencyState.SAVED,
            ResidencyState.RECOVERING,
        ),
        ResidencyState.WARM to setOf(
            ResidencyState.ACTIVE,
            ResidencyState.SAVED,
            ResidencyState.HIBERNATED,
            ResidencyState.RECOVERING,
        ),
        ResidencyState.SAVED to setOf(
            ResidencyState.ACTIVE,
            ResidencyState.WARM,
            ResidencyState.HIBERNATED,
            ResidencyState.RECOVERING,
        ),
        ResidencyState.HIBERNATED to setOf(
            ResidencyState.RECOVERING,
        ),
        ResidencyState.RECOVERING to setOf(
            ResidencyState.ACTIVE,
            ResidencyState.SAVED,
            ResidencyState.HIBERNATED,
        ),
    )

    fun reduce(tab: Tab, event: TabEvent): Tab = when (event) {
        is TabEvent.PresentAs -> tab.copy(presentationState = event.state)
        is TabEvent.SetPinned -> tab.copy(pinned = event.pinned)
        TabEvent.RendererGone -> tab.copy(
            residencyState = ResidencyState.RECOVERING,
            crashRecoveryCount = tab.crashRecoveryCount + 1,
        )
        TabEvent.RendererRecovered -> transitionResidency(tab, ResidencyState.ACTIVE)
        is TabEvent.SetResidency -> transitionResidency(tab, event.state)
    }

    private fun transitionResidency(tab: Tab, target: ResidencyState): Tab {
        if (tab.residencyState == target) return tab
        if (target !in legalResidencyTransitions.getValue(tab.residencyState)) {
            throw IllegalTabTransition(
                "Illegal renderer transition ${tab.residencyState} -> $target for ${tab.id.value}",
            )
        }
        return tab.copy(residencyState = target)
    }
}
