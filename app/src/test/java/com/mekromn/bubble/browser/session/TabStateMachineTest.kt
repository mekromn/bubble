package com.mekromn.bubble.browser.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TabStateMachineTest {
    private fun tab(residency: ResidencyState = ResidencyState.HIBERNATED): Tab = Tab(
        id = TabId("00000000-0000-0000-0000-000000000001"),
        createdAt = 1L,
        lastActivatedAt = 1L,
        sortIndex = 0L,
        lastCommittedUrl = "https://example.com",
        residencyState = residency,
    )

    @Test
    fun presentationDoesNotForceRendererResidency() {
        val source = tab(ResidencyState.SAVED)
        val head = TabStateMachine.reduce(source, TabEvent.PresentAs(PresentationState.HEAD))
        assertEquals(PresentationState.HEAD, head.presentationState)
        assertEquals(ResidencyState.SAVED, head.residencyState)
    }

    @Test
    fun rendererDeathMovesToRecoveringAndCountsRecovery() {
        val recovering = TabStateMachine.reduce(tab(ResidencyState.ACTIVE), TabEvent.RendererGone)
        assertEquals(ResidencyState.RECOVERING, recovering.residencyState)
        assertEquals(1, recovering.crashRecoveryCount)
    }

    @Test
    fun hibernatedRendererMustRecoverBeforeBecomingActive() {
        assertThrows(IllegalTabTransition::class.java) {
            TabStateMachine.reduce(
                tab(ResidencyState.HIBERNATED),
                TabEvent.SetResidency(ResidencyState.ACTIVE),
            )
        }
    }

    @Test
    fun recoveringRendererCanBecomeActive() {
        val recovered = TabStateMachine.reduce(
            tab(ResidencyState.RECOVERING),
            TabEvent.RendererRecovered,
        )
        assertEquals(ResidencyState.ACTIVE, recovered.residencyState)
    }

    @Test
    fun recoveringKeepLiveHeadCanBecomeWarm() {
        val warmed = TabStateMachine.reduce(
            tab(ResidencyState.RECOVERING),
            TabEvent.SetResidency(ResidencyState.WARM),
        )
        assertEquals(ResidencyState.WARM, warmed.residencyState)
    }
}
