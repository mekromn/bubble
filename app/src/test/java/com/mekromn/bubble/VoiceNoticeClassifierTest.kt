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

    @Test fun contactInfoPromotesSenderAndKeepsNumberAsSecondaryContext() {
        val info = VoiceContactPolicy.extract("New message from Alice Example", "Call back at 619-555-0123", "sms")
        assertEquals("Alice Example", info.displayName)
        assertEquals("619-555-0123", info.phone)
        assertEquals("Alice Example", VoiceContactPolicy.title(VoiceNoticeKind.MESSAGE, "New message from Alice Example", info))
        assertEquals("Google Voice · 619-555-0123", VoiceContactPolicy.subText(VoiceNoticeKind.MESSAGE, info))
        assertEquals("tel:6195550123", VoiceContactPolicy.telUri(requireNotNull(info.phone)))
    }

    @Test fun phoneOnlySenderStillProducesUsefulContactNotification() {
        val info = VoiceContactPolicy.extract("Text from +1 858-555-0199", "Ping", "sms")
        assertNull(info.displayName)
        assertEquals("+1 858-555-0199", info.phone)
        assertEquals("+1 858-555-0199", VoiceContactPolicy.title(VoiceNoticeKind.MESSAGE, "New message", info))
        assertEquals("tel:+18585550199", VoiceContactPolicy.telUri(requireNotNull(info.phone)))
    }

    @Test fun genericVoiceTitlesAreNotMisidentifiedAsContacts() {
        val info = VoiceContactPolicy.extract("Google Voice", "You have a new message", "message")
        assertNull(info.displayName)
        assertNull(info.phone)
        assertEquals("New Google Voice message", VoiceContactPolicy.title(VoiceNoticeKind.MESSAGE, "Google Voice", info))
        assertEquals("Google Voice · Messages", VoiceContactPolicy.subText(VoiceNoticeKind.MESSAGE, info))
        assertNull(VoiceContactPolicy.telUri("123"))
        assertEquals("tel:6195550123", VoiceContactPolicy.telUri("(619) 555-0123"))
    }
}
