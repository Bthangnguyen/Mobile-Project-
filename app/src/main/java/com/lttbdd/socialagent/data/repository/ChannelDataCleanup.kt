package com.lttbdd.socialagent.data.repository

import androidx.room.withTransaction
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.entity.ChannelCleanupReceipt
import com.lttbdd.socialagent.core.model.ChannelType
import javax.inject.Inject

fun interface ChannelDataCleanup {
    suspend fun clear(type: ChannelType, operationId: String)
    /**
     * Dấu vân tay ổn định qua khởi động lại của phiên Facebook SDK, chỉ khi phiên đó thuộc
     * [expectedUserId] (ID người cấp quyền đã lưu cùng token bị ngắt). Trả null nếu không có phiên
     * hoặc phiên thuộc người dùng khác; ném [UnverifiedFacebookSessionException] nếu không xác minh được.
     */
    suspend fun currentFacebookSession(expectedUserId: String?): String? = null

    /** Hoàn tất đăng xuất khi phiên còn khớp. Phiên mới vẫn được giữ lại. */
    suspend fun signOutFacebook(expectedSession: String) {
        error("Không có bộ xử lý đăng xuất Facebook.")
    }

    /**
     * Đăng xuất phiên Facebook SDK hiện tại, không đối chiếu chủ sở hữu. Chỉ dùng khi người dùng
     * đã chọn rõ ràng cho phiên không xác minh được.
     */
    suspend fun forceSignOutFacebook() {
        error("Không có bộ xử lý đăng xuất Facebook.")
    }
}

class LocalChannelDataCleanup @Inject constructor(
    private val database: AppDatabase,
) : ChannelDataCleanup {
    private val facebookSdk = FacebookSdkDisconnectAdapter()

    override suspend fun clear(type: ChannelType, operationId: String) {
        database.withTransaction {
            val cleanupDao = database.channelCleanupDao()
            if (cleanupDao.getCompletedOperation(type) == operationId) return@withTransaction
            database.commentDao().deleteByChannel(type)
            database.metricsDao().deleteByChannel(type)
            cleanupDao.deferProtectedPosts(type)
            database.postDao().deleteByChannel(type)
            database.draftDao().deleteByChannel(type)
            // Ghi dấu cùng giao dịch để việc thử lại không xoá dữ liệu tạo sau lần dọn này.
            cleanupDao.recordCompletion(ChannelCleanupReceipt(type, operationId))
        }
    }

    override suspend fun currentFacebookSession(expectedUserId: String?): String? =
        facebookSdk.currentSessionKey(expectedUserId)

    override suspend fun signOutFacebook(expectedSession: String) = facebookSdk.signOut(expectedSession)

    override suspend fun forceSignOutFacebook() = facebookSdk.forceSignOut()
}
