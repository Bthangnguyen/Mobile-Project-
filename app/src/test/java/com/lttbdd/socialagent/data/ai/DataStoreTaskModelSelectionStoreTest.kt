package com.lttbdd.socialagent.data.ai

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mỗi test dùng một tệp DataStore riêng (tên ngẫu nhiên) nên không phụ thuộc thứ tự chạy.
 * Chạy bằng Robolectric như test DataStore khác của nhóm: DataStore bản JVM thuần không ghi đè
 * được tệp có sẵn trên Windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataStoreTaskModelSelectionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File

    private fun newStore(): DataStoreTaskModelSelectionStore {
        file = context.preferencesDataStoreFile("task_models_${UUID.randomUUID()}")
        return storeFor(file)
    }

    private fun storeFor(target: File) = DataStoreTaskModelSelectionStore(
        PreferenceDataStoreFactory.create(
            corruptionHandler = taskModelCorruptionHandler(),
            scope = scope,
            produceFile = { target },
        ),
    )

    private val reopenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Mở lại store trên tệp đang dùng sau khi đóng store cũ. Phải đợi scope cũ kết thúc hẳn
     * (`cancelAndJoin`) vì `cancel()` chạy bất đồng bộ: DataStore chỉ nhả tệp khi scope hoàn tất,
     * nếu mở ngay sẽ có lúc gặp "There are multiple DataStores active for the same file".
     */
    private suspend fun reopenStore(): DataStoreTaskModelSelectionStore {
        scope.coroutineContext[Job]?.cancelAndJoin()
        return DataStoreTaskModelSelectionStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = taskModelCorruptionHandler(),
                scope = reopenScope,
                produceFile = { file },
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        reopenScope.cancel()
    }

    @Test
    fun `saves, reads, replaces and clears each task independently`() = runBlocking {
        val store = newStore()
        assertTrue(LlmTask.entries.all { store.get(it) == null })
        val write = TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna")
        val classify = TaskModelSelection(LlmProvider.GEMINI, "gemini-2.5-flash-lite")
        store.save(LlmTask.WRITE_POST, write)
        store.save(LlmTask.CLASSIFY_COMMENT, classify)
        assertEquals(write, store.get(LlmTask.WRITE_POST))
        assertEquals(classify, store.get(LlmTask.CLASSIFY_COMMENT))
        assertNull(store.get(LlmTask.GENERATE_IMAGE))

        val replacement = TaskModelSelection(LlmProvider.GEMINI, "gemini-2.5-flash")
        store.save(LlmTask.WRITE_POST, replacement)
        assertEquals(replacement, store.get(LlmTask.WRITE_POST))
        assertEquals(classify, store.get(LlmTask.CLASSIFY_COMMENT))

        store.clear(LlmTask.WRITE_POST)
        assertNull(store.get(LlmTask.WRITE_POST))
        assertEquals(classify, store.get(LlmTask.CLASSIFY_COMMENT))
    }

    @Test
    fun `selection survives creating a new store on the same file`() = runBlocking {
        val first = newStore()
        first.save(LlmTask.WRITE_POST, TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna"))
        val reopened = reopenStore()
        assertEquals(TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna"), reopened.get(LlmTask.WRITE_POST))
    }

    @Test
    fun `a corrupted file falls back to defaults instead of crashing`() = runBlocking {
        file = context.preferencesDataStoreFile("task_models_${UUID.randomUUID()}")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x0f))
        val store = storeFor(file)
        assertNull(store.get(LlmTask.WRITE_POST))
        store.save(LlmTask.WRITE_POST, TaskModelSelection(LlmProvider.GEMINI, "gemini-2.5-flash"))
        assertEquals("gemini-2.5-flash", store.get(LlmTask.WRITE_POST)?.modelId)
    }

    @Test
    fun `ignores stored provider names it does not know`() = runBlocking {
        val store = newStore()
        store.save(LlmTask.WRITE_POST, TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna"))
        // Ghi trực tiếp một tên provider lạ qua cùng tệp để mô phỏng dữ liệu từ phiên bản khác.
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(raw.contains("OPENAI"))
        file.writeBytes(raw.replace("OPENAI", "XXXXXX").toByteArray(Charsets.ISO_8859_1))
        val reopened = reopenStore()
        assertNull(reopened.get(LlmTask.WRITE_POST))
    }

    @Test
    fun `persisted file holds only provider and model id`() = runBlocking {
        val store = newStore()
        store.save(LlmTask.WRITE_POST, TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna"))
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(raw.contains("OPENAI") && raw.contains("gpt-6-luna"))
        assertFalse(raw.contains("key", ignoreCase = true))
    }
}
