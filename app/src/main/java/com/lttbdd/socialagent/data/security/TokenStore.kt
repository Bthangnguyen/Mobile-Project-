package com.lttbdd.socialagent.data.security

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.OAuthToken
import kotlinx.coroutines.flow.Flow

/**
 * Interface định nghĩa thao tác lưu trữ OAuthToken an toàn.
 */
interface TokenStore {
    suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken)
    fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?>
    suspend fun removeToken(channelType: ChannelType, accountId: String)
}
