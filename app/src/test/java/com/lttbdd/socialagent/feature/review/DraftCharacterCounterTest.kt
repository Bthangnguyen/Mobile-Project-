package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.core.model.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCharacterCounterTest {

    @Test
    fun `evaluate uses ChannelType maxChars for Facebook and LinkedIn`() {
        val fb = DraftCharacterCounter.evaluate("Hello", ChannelType.FACEBOOK)
        assertEquals(5, fb.currentLength)
        assertEquals(ChannelType.FACEBOOK.maxChars, fb.maxChars)
        assertEquals(ChannelType.FACEBOOK.maxChars - 5, fb.remainingChars)
        assertEquals(CharacterLimitLevel.NORMAL, fb.level)
        assertFalse(fb.isOverLimit)

        val li = DraftCharacterCounter.evaluate("Hello", ChannelType.LINKEDIN)
        assertEquals(5, li.currentLength)
        assertEquals(ChannelType.LINKEDIN.maxChars, li.maxChars)
        assertEquals(ChannelType.LINKEDIN.maxChars - 5, li.remainingChars)
        assertEquals(CharacterLimitLevel.NORMAL, li.level)
        assertFalse(li.isOverLimit)
    }

    @Test
    fun `evaluate marks NEAR_LIMIT at 90 percent and exact limit`() {
        val ninetyPercent = "a".repeat(2_700) // 90% of 3_000
        val nearStatus = DraftCharacterCounter.evaluate(ninetyPercent, ChannelType.LINKEDIN)
        assertEquals(CharacterLimitLevel.NEAR_LIMIT, nearStatus.level)
        assertFalse(nearStatus.isOverLimit)
        assertEquals(300, nearStatus.remainingChars)

        val exactLimit = "a".repeat(ChannelType.LINKEDIN.maxChars)
        val exactStatus = DraftCharacterCounter.evaluate(exactLimit, ChannelType.LINKEDIN)
        assertEquals(CharacterLimitLevel.NEAR_LIMIT, exactStatus.level)
        assertFalse(exactStatus.isOverLimit)
        assertEquals(0, exactStatus.remainingChars)
    }

    @Test
    fun `evaluate marks EXCEEDED when text surpasses channel limit`() {
        val overLinkedIn = "a".repeat(ChannelType.LINKEDIN.maxChars + 12)
        val status = DraftCharacterCounter.evaluate(overLinkedIn, ChannelType.LINKEDIN)

        assertEquals(CharacterLimitLevel.EXCEEDED, status.level)
        assertTrue(status.isOverLimit)
        assertEquals(-12, status.remainingChars)
        assertEquals(12, status.excessChars)
        assertFalse(DraftCharacterCounter.canSave(overLinkedIn, ChannelType.LINKEDIN))
        // Same 3,012-char text is valid on Facebook (63,206 limit)
        assertTrue(DraftCharacterCounter.canSave(overLinkedIn, ChannelType.FACEBOOK))
    }

    @Test
    fun `canSave rejects blank text even within channel limit`() {
        assertFalse(DraftCharacterCounter.canSave("", ChannelType.FACEBOOK))
        assertFalse(DraftCharacterCounter.canSave("   ", ChannelType.LINKEDIN))
        assertTrue(DraftCharacterCounter.canSave("Valid post", ChannelType.LINKEDIN))
    }
}
