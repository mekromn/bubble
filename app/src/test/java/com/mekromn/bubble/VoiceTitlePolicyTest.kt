package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class VoiceTitlePolicyTest {
    @Test fun screenshotMessageTitleParsesUnreadMessage() {
        val signal = VoiceTitlePolicy.parse("Voice - (1) Messages", "https://voice.google.com/u/0/messages")
        assertNotNull(signal)
        assertEquals(VoiceNoticeKind.MESSAGE, signal!!.kind)
        assertEquals(1, signal.unreadCount)
        assertFalse(signal.ringing)
    }

    @Test fun noCountEstablishesZeroBaselineAndLaterIncrementIsDetectable() {
        val baseline = VoiceTitlePolicy.parse("Voice - Messages", "https://voice.google.com/u/0/messages")!!
        val later = VoiceTitlePolicy.parse("Voice - (2) Messages", "https://voice.google.com/u/0/messages")!!
        assertEquals(0, baseline.unreadCount)
        assertEquals(2, later.unreadCount)
        assertTrue(later.unreadCount > baseline.unreadCount)
    }

    @Test fun voicemailAndCallTitlesRouteCorrectly() {
        assertEquals(VoiceNoticeKind.VOICEMAIL,
            VoiceTitlePolicy.parse("Voice - (3) Voicemail", "https://voice.google.com/u/0/voicemail")!!.kind)
        val call = VoiceTitlePolicy.parse("Incoming call - Google Voice", "https://voice.google.com/u/0/calls")!!
        assertEquals(VoiceNoticeKind.INCOMING_CALL, call.kind)
        assertTrue(call.ringing)
    }

    @Test fun nonVoiceOriginsNeverProduceFallbackSignals() {
        assertNull(VoiceTitlePolicy.parse("Voice - (1) Messages", "https://chatgpt.com/"))
        assertNull(VoiceTitlePolicy.parse("Voice - (1) Messages", "https://voice.google.com.evil.example/"))
    }
}
