package com.lttbdd.socialagent.data.security

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.OAuthToken
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Định danh lần ghi đang chờ. Token được mã hóa và lưu riêng. */
@Serializable
data class PendingChannelWrite(val id: String, val type: ChannelType, val accountId: String)

/**
 * Interface định nghĩa thao tác lưu trữ OAuthToken an toàn.
 */
interface TokenStore {
    suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken)
    fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?>
    /** Xóa nguyên tử token chính và lần ghi đang chờ của tài khoản. */
    suspend fun removeToken(channelType: ChannelType, accountId: String)

    suspend fun stageToken(write: PendingChannelWrite, token: OAuthToken)
    suspend fun loadPendingWrites(): List<PendingChannelWrite>
    suspend fun loadStagedToken(write: PendingChannelWrite): OAuthToken?

    /** Cập nhật token chính thức và dọn dữ liệu tạm trong cùng một lần ghi. */
    suspend fun finishPendingWrite(write: PendingChannelWrite, commit: Boolean)
}
