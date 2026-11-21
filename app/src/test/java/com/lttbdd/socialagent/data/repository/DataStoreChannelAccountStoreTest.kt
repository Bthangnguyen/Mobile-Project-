package com.lttbdd.socialagent.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataStoreChannelAccountStoreTest {
    @Test
    fun `khoi phuc tep metadata hong va giu nguyen tep token`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metadataFile = context.preferencesDataStoreFile("social_agent_channel_accounts")
        val tokenFile = context.preferencesDataStoreFile("social_agent_secure_tokens")
        val tokenBytes = byteArrayOf(1, 2, 3)
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeBytes(byteArrayOf(0x0f))
        tokenFile.writeBytes(tokenBytes)
        val store = DataStoreChannelAccountStore(context)

        assertTrue(store.load().isEmpty())
        val account = StoredChannelAccount(ChannelType.FACEBOOK, "page", "Page")
        val other = account.copy(type = ChannelType.LINKEDIN, tokenWriteId = "pending")
        store.upsert(other)
        store.upsert(account)
        assertEquals(listOf(other, account), store.load())
        store.remove(other.type, other.accountId)
        assertEquals(listOf(account), store.load())
        assertArrayEquals(tokenBytes, tokenFile.readBytes())
    }
}
