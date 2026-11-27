package com.lttbdd.socialagent.feature.publishing

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.publishing.PublishErrorReason
import com.lttbdd.socialagent.data.publishing.PublishQueueRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishErrorMessagesTest {

    @Test
    fun `channel reason codes map to their own message`() {
        val expected = mapOf(
            PublishErrorReason.NETWORK_UNAVAILABLE to R.string.publish_error_network_unavailable,
            PublishErrorReason.RATE_LIMITED to R.string.publish_error_rate_limited,
            PublishErrorReason.SERVER_ERROR to R.string.publish_error_server_error,
            PublishErrorReason.PERMISSION_DENIED to R.string.publish_error_permission_denied,
            PublishErrorReason.AUTH_EXPIRED to R.string.publish_error_auth_expired,
            PublishErrorReason.CONTENT_REJECTED to R.string.publish_error_content_rejected,
            PublishErrorReason.DUPLICATE_CONTENT to R.string.publish_error_duplicate_content,
        )
        expected.forEach { (code, message) -> assertEquals(code, message, PublishErrorMessages.messageFor(code)) }
    }

    @Test
    fun `queue reason codes stay in sync with the repository`() {
        // feature/ viết lại mã của hàng đợi để không import data/; đổi tên ở một bên thì test này đỏ.
        val expected = mapOf(
            PublishQueueRepository.REASON_NOT_SENT to R.string.publish_error_network_unavailable,
            PublishQueueRepository.REASON_STORAGE to R.string.publish_error_storage,
            PublishQueueRepository.REASON_DRAFT_MISSING to R.string.publish_error_draft_missing,
            PublishQueueRepository.REASON_NOT_APPROVED to R.string.publish_error_not_approved,
            PublishQueueRepository.REASON_NO_CLIENT to R.string.publish_error_channel_not_supported,
            PublishQueueRepository.REASON_RETRY_LIMIT to R.string.publish_error_retry_limit,
        )
        expected.forEach { (code, message) -> assertEquals(code, message, PublishErrorMessages.messageFor(code)) }
        assertNull(PublishErrorMessages.messageFor(PublishQueueRepository.REASON_OUTCOME_UNKNOWN))
    }

    @Test
    fun `details after the code are ignored`() {
        assertEquals(
            R.string.publish_error_retry_limit,
            PublishErrorMessages.messageFor("retry_limit_reached (rate_limited)"),
        )
        assertNull(PublishErrorMessages.messageFor("outcome_unknown (SocketTimeoutException)"))
        assertEquals(R.string.publish_error_permission_denied, PublishErrorMessages.messageFor("  PERMISSION_DENIED "))
    }

    @Test
    fun `unknown reason shows a generic message, never the raw text`() {
        assertEquals(
            R.string.publish_error_unknown,
            PublishErrorMessages.messageFor("HTTP 400 https://graph.facebook.com/v19.0/me?access_token=SECRET"),
        )
    }

    @Test
    fun `missing reason shows nothing`() {
        assertNull(PublishErrorMessages.messageFor(null))
        assertNull(PublishErrorMessages.messageFor(""))
        assertNull(PublishErrorMessages.messageFor("   "))
    }
}
