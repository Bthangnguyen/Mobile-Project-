package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAccountMetadataTest {
    private val account = StoredChannelAccount(ChannelType.FACEBOOK, "page", "Page")

    @Test
    fun `cap nhat va xoa chi thay doi dung loai kenh va id`() {
        val raw = """[{"type":"LINKEDIN","accountId":"page","displayName":"Khác","tokenWriteId":"pending","extra":42}]"""
        val updated = ChannelAccountMetadata.upsert(raw, account)
        val renamed = ChannelAccountMetadata.upsert(updated, account.copy(displayName = "Tên mới"))

        assertEquals(2, ChannelAccountMetadata.decode(renamed).size)
        assertEquals(raw, ChannelAccountMetadata.remove(renamed, account.type, account.accountId))
    }

    @Test
    fun `ma lan ghi di cung metadata sau khi doc lai`() {
        val committed = account.copy(tokenWriteId = "write-1")

        val encoded = ChannelAccountMetadata.upsert(null, committed)

        assertEquals(listOf(committed), ChannelAccountMetadata.decode(encoded))
    }

    @Test
    fun `bo qua muc hong va loai kenh la nhung van doc kenh hop le`() {
        val raw = """[
            {"type":"FACEBOOK","accountId":"page","displayName":"Page"},
            {"type":"NEW_PROVIDER","accountId":"new","displayName":"N","extra":"preserve"},
            {"type":"LINKEDIN","accountId":"missing-name"},
            {"type":"FACEBOOK","accountId":"" ,"displayName":"Invalid"},
            null
        ]"""

        assertEquals(listOf(account), ChannelAccountMetadata.decode(raw))

        val updated = ChannelAccountMetadata.upsert(raw, account.copy(displayName = "Updated"))
        assertEquals(listOf(account.copy(displayName = "Updated")), ChannelAccountMetadata.decode(updated))
        assertTrue(updated.contains("""{"type":"NEW_PROVIDER","accountId":"new","displayName":"N","extra":"preserve"}"""))
    }

    @Test
    fun `metadata sai cu phap cho phep ket noi va doc lai kenh moi`() {
        val broken = "[{"
        assertTrue(ChannelAccountMetadata.decode(broken).isEmpty())
        assertEquals(listOf(account), ChannelAccountMetadata.decode(ChannelAccountMetadata.upsert(broken, account)))
    }
}
