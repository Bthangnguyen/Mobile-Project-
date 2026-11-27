package com.lttbdd.socialagent.data.review

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileDraftVersionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `recordVersion stores newest first with monotonic version numbers and survives reopen`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = tempFolder.newFolder("versions")
        val store = FileDraftVersionStore(baseDir = dir, ioDispatcher = dispatcher)

        val v1 = store.recordVersion("draft-1", "Nội dung v1", DraftVersionSource.AI_GENERATED, 1_000L)
        val v2 = store.recordVersion("draft-1", "Nội dung v2", DraftVersionSource.MANUAL_EDIT, 2_000L)

        assertEquals(1, v1.versionNumber)
        assertEquals(2, v2.versionNumber)

        val reopened = FileDraftVersionStore(baseDir = dir, ioDispatcher = dispatcher)
        val loaded = reopened.getVersions("draft-1")
        assertEquals(listOf(2, 1), loaded.map { it.versionNumber })
        assertEquals(listOf("Nội dung v2", "Nội dung v1"), loaded.map { it.text })
    }

    @Test
    fun `recordVersion skips duplicate consecutive text except for RESTORED`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileDraftVersionStore(baseDir = tempFolder.newFolder("dedup"), ioDispatcher = dispatcher)

        val v1 = store.recordVersion("draft-1", "Cùng nội dung", DraftVersionSource.MANUAL_EDIT, 1_000L)
        val duplicate = store.recordVersion("draft-1", "Cùng nội dung", DraftVersionSource.MANUAL_EDIT, 2_000L)
        assertEquals(v1.id, duplicate.id)
        assertEquals(1, store.getVersions("draft-1").size)

        val restored = store.recordVersion("draft-1", "Cùng nội dung", DraftVersionSource.RESTORED, 3_000L)
        assertEquals(2, restored.versionNumber)
        assertEquals(2, store.getVersions("draft-1").size)
    }

    @Test
    fun `recordVersion caps history at maxVersionsPerDraft keeping newest`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileDraftVersionStore(
            baseDir = tempFolder.newFolder("cap"),
            ioDispatcher = dispatcher,
            maxVersionsPerDraft = 3,
        )

        repeat(5) { idx ->
            store.recordVersion("draft-cap", "Text $idx", DraftVersionSource.MANUAL_EDIT, idx.toLong())
        }

        val versions = store.getVersions("draft-cap")
        assertEquals(listOf(5, 4, 3), versions.map { it.versionNumber })
        assertEquals(listOf("Text 4", "Text 3", "Text 2"), versions.map { it.text })
    }

    @Test
    fun `corrupt file returns empty list safely and supports safe hashed filenames`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = tempFolder.newFolder("corrupt")
        val store = FileDraftVersionStore(baseDir = dir, ioDispatcher = dispatcher)

        // ID chứa ký tự đặc biệt / path traversal vẫn lưu an toàn trong thư mục baseDir
        store.recordVersion("../escape/draft?1", "An toàn", DraftVersionSource.AI_GENERATED, 1_000L)
        val savedFiles = dir.listFiles().orEmpty()
        assertEquals(1, savedFiles.size)
        assertTrue(savedFiles.single().parentFile?.canonicalPath == dir.canonicalPath)

        // Ghi hỏng JSON và kiểm tra không crash
        savedFiles.single().writeText("{not-valid-json", Charsets.UTF_8)
        assertTrue(store.getVersions("../escape/draft?1").isEmpty())
    }

    @Test
    fun `observeVersions emits updates when recording and clearing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileDraftVersionStore(baseDir = tempFolder.newFolder("observe"), ioDispatcher = dispatcher)
        val emissions = mutableListOf<List<DraftVersion>>()

        val job = launch {
            store.observeVersions("draft-obs").take(3).toList(emissions)
        }
        advanceUntilIdle()

        store.recordVersion("draft-obs", "Bản 1", DraftVersionSource.AI_GENERATED, 1_000L)
        advanceUntilIdle()

        store.clearVersions("draft-obs")
        advanceUntilIdle()
        job.join()

        assertEquals(3, emissions.size)
        assertTrue(emissions[0].isEmpty())
        assertEquals(listOf("Bản 1"), emissions[1].map { it.text })
        assertTrue(emissions[2].isEmpty())
    }

    @Test
    fun `recovers from backup file if primary file was interrupted and leaves no temp files`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = tempFolder.newFolder("atomic")
        val store = FileDraftVersionStore(baseDir = dir, ioDispatcher = dispatcher)

        store.recordVersion("draft-atomic", "Bản 1", DraftVersionSource.AI_GENERATED, 1_000L)
        store.recordVersion("draft-atomic", "Bản 2", DraftVersionSource.MANUAL_EDIT, 2_000L)

        // Sau khi ghi nguyên tử hoàn tất chỉ còn đúng 1 tệp .json (không còn .new hay .bak)
        val files = dir.listFiles().orEmpty()
        assertEquals(1, files.size)
        val primary = files.single()
        assertTrue(primary.name.endsWith(".json"))

        // Giả lập ngắt giữa chừng: chỉ còn tệp .bak, tệp chính bị mất
        val backup = File(dir, "${primary.name}.bak")
        assertTrue(primary.renameTo(backup))
        val recovered = store.getVersions("draft-atomic")
        assertEquals(listOf("Bản 2", "Bản 1"), recovered.map { it.text })
    }

    @Test
    fun `repairs corrupt primary from valid backup before recording next version`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = tempFolder.newFolder("repair-corrupt")
        val store = FileDraftVersionStore(baseDir = dir, ioDispatcher = dispatcher)

        store.recordVersion("draft-repair", "Bản 1", DraftVersionSource.AI_GENERATED, 1_000L)
        store.recordVersion("draft-repair", "Bản 2", DraftVersionSource.MANUAL_EDIT, 2_000L)

        val primary = dir.listFiles().orEmpty().single()
        val backup = File(dir, "${primary.name}.bak")
        // Tạo bản sao lưu hợp lệ và làm hỏng tệp chính
        backup.writeBytes(primary.readBytes())
        primary.writeText("{corrupt-json", Charsets.UTF_8)

        // Ghi phiên bản mới phải phục hồi từ backup hợp lệ trước, không làm mất Bản 1 và Bản 2
        store.recordVersion("draft-repair", "Bản 3", DraftVersionSource.MANUAL_EDIT, 3_000L)

        val reopened = FileDraftVersionStore(baseDir = dir, ioDispatcher = dispatcher)
        val versions = reopened.getVersions("draft-repair")
        assertEquals(listOf("Bản 3", "Bản 2", "Bản 1"), versions.map { it.text })
        assertEquals(1, dir.listFiles().orEmpty().size)
    }
}
