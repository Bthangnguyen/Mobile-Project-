package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelType

/**
 * Metadata của một tài khoản kênh để khôi phục danh sách khi mở lại app.
 * KHÔNG chứa token (token nằm trong TokenStore, mã hóa bằng Keystore).
 */
data class StoredChannelAccount(
    val type: ChannelType,
    val accountId: String,
    val displayName: String,
)

/**
 * Nơi lưu metadata tài khoản. Hiện dùng DataStore ([DataStoreChannelAccountStore]);
 * khi nhóm A có bảng Room cho kênh (nhánh #56) chỉ cần thay bản cài đặt, không đổi repository.
 */
interface ChannelAccountStore {
    suspend fun load(): List<StoredChannelAccount>
    suspend fun save(accounts: List<StoredChannelAccount>)
}
