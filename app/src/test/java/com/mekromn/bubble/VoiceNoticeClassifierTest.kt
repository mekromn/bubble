package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class VoiceNoticeClassifierTest {
    @Test fun onlyExactHttpsGoogleVoiceOriginMatches() {
        assertTrue(Policy.isVoice("https://voice.google.com/"))
        assertTrue(Policy.isVoice("https://voice.google.com/u/0/messages"))
        assertTrue(Policy.isVoice("https://voice.google.com^geckoViewSessionContextId=gvctx123"))
        assertFalse(Policy.isVoice("http://voice.google.com/"))
        assertFalse(Policy.isVoice("https://evil.voice.google.com/"))
        assertFalse(Policy.isVoice("https://voice.google.com.evil.example/"))
        assertFalse(Policy.isVoice("https://chatgpt.com/"))
    }

    @Test fun voiceAlertsRouteToSeparateChannels() {
        assertEquals(VoiceNoticeKind.INCOMING_CALL, VoiceNoticeClassifier.classify("Incoming call", "Alice is calling", "call"))
        assertEquals(VoiceNoticeKind.MESSAGE, VoiceNoticeClassifier.classify("New message", "Text from Alice", "sms"))
        assertEquals(VoiceNoticeKind.MISSED_CALL, VoiceNoticeClassifier.classify("Missed call", "Alice", ""))
        assertEquals(VoiceNoticeKind.VOICEMAIL, VoiceNoticeClassifier.classify("New voicemail", "Transcript ready", "vm"))
        assertEquals(VoiceNoticeKind.OTHER, VoiceNoticeClassifier.classify("Google Voice", "Account update", "misc"))
        assertEquals(5, VoiceNoticeKind.entries.map { it.channel }.toSet().size)
    }
}
