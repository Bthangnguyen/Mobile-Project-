package com.lttbdd.socialagent.data.review

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftApprovalGuardTest {

    private fun sampleDraft(
        text: String = "Nội dung bản nháp hợp lệ",
        approved: Boolean = false,
    ) = Draft(
        id = "draft-1",
        topicId = "topic-1",
        channel = ChannelType.FACEBOOK,
        text = text,
        approved = approved,
    )

    @Test
    fun `sanitizeAgentDraft always forces approved to false even if input is true`() {
        val preApprovedFromAgent = sampleDraft(approved = true)
        val sanitized = DraftApprovalGuard.sanitizeAgentDraft(preApprovedFromAgent)

        assertFalse(sanitized.approved)
        assertEquals(preApprovedFromAgent.text, sanitized.text)
        assertEquals(preApprovedFromAgent.id, sanitized.id)
    }

    @Test
    fun `onDraftContentEdited revokes approval so modified draft must be re-approved`() {
        val edited = DraftApprovalGuard.onDraftContentEdited(sampleDraft(approved = true))
        assertFalse(edited.approved)
    }

    @Test
    fun `unapproved draft is blocked from publishing`() {
        val unapproved = sampleDraft(approved = false)

        assertEquals(
            PublishEligibility.BlockedNotApproved,
            DraftApprovalGuard.checkPublishEligibility(unapproved),
        )
        assertFalse(DraftApprovalGuard.canPublish(unapproved))
    }

    @Test
    fun `approved draft with blank text is blocked from publishing`() {
        val blankDraft = sampleDraft(text = "   ", approved = true)

        assertEquals(
            PublishEligibility.BlockedEmptyContent,
            DraftApprovalGuard.checkPublishEligibility(blankDraft),
        )
        assertFalse(DraftApprovalGuard.canPublish(blankDraft))
    }

    @Test
    fun `approved draft with non-blank text is eligible to publish`() {
        val approved = sampleDraft(approved = true)

        assertEquals(
            PublishEligibility.Allowed,
            DraftApprovalGuard.checkPublishEligibility(approved),
        )
        assertTrue(DraftApprovalGuard.canPublish(approved))
    }
}
