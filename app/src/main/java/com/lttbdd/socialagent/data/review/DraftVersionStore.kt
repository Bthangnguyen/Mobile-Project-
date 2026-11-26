package com.lttbdd.socialagent.data.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Kho lưu trữ ngoại tuyến cho lịch sử phiên bản của từng bản nháp (F4, Issue #62).
 * Không thay đổi schema `AppDatabase` trong `core/database/`.
 */
interface DraftVersionStore {

    /** Quan sát danh sách phiên bản của [draftId] theo thứ tự mới nhất trước. */
    fun observeVersions(draftId: String): Flow<List<DraftVersion>>

    /** Đọc danh sách phiên bản của [draftId] theo thứ tự mới nhất trước. */
    suspend fun getVersions(draftId: String): List<DraftVersion>

    /**
     * Ghi nhận một phiên bản mới cho [draftId].
     * Nếu [source] không phải [DraftVersionSource.RESTORED] và phiên bản mới nhất đã có cùng [text],
     * trả về phiên bản mới nhất đó thay vì ghi trùng lặp.
     */
    suspend fun recordVersion(
        draftId: String,
        text: String,
        source: DraftVersionSource,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): DraftVersion

    /** Xóa toàn bộ lịch sử phiên bản của [draftId]. */
    suspend fun clearVersions(draftId: String)
}

/** Triển khai rỗng dùng mặc định cho các test không cần lưu lịch sử phiên bản. */
object NoOpDraftVersionStore : DraftVersionStore {
    override fun observeVersions(draftId: String): Flow<List<DraftVersion>> = emptyFlow()
    override suspend fun getVersions(draftId: String): List<DraftVersion> = emptyList()
    override suspend fun recordVersion(
        draftId: String,
        text: String,
        source: DraftVersionSource,
        createdAtMillis: Long,
    ): DraftVersion = DraftVersion(
        id = UUID.randomUUID().toString(),
        draftId = draftId,
        versionNumber = 1,
        text = text,
        source = source,
        createdAtMillis = createdAtMillis,
    )

    override suspend fun clearVersions(draftId: String) = Unit
}

/**
 * Lưu lịch sử phiên bản mỗi bản nháp thành một tệp JSON riêng trên bộ nhớ trong (`filesDir/draft_versions/`).
 *
 * - Tên tệp dùng SHA-256 của `draftId` để tránh ký tự đặc biệt / path traversal.
 * - Ghi nguyên tử (ghi tệp `.tmp` rồi thay thế tệp đích) và đồng bộ bằng [Mutex].
 * - Giới hạn tối đa [maxVersionsPerDraft] phiên bản gần nhất cho mỗi bản nháp để tránh phình dung lượng.
 */
class FileDraftVersionStore(
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxVersionsPerDraft: Int = MAX_VERSIONS_PER_DRAFT,
) : DraftVersionStore {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val changeNotifications = MutableSharedFlow<String>(extraBufferCapacity = 32)

    override fun observeVersions(draftId: String): Flow<List<DraftVersion>> = flow {
        emit(getVersions(draftId))
        changeNotifications
            .filter { changedId -> changedId == draftId }
            .collect {
                emit(getVersions(draftId))
            }
    }

    override suspend fun getVersions(draftId: String): List<DraftVersion> = withContext(ioDispatcher) {
        mutex.withLock {
            readVersionsLocked(draftId)
        }
    }

    override suspend fun recordVersion(
        draftId: String,
        text: String,
        source: DraftVersionSource,
        createdAtMillis: Long,
    ): DraftVersion = withContext(ioDispatcher) {
        val recorded = mutex.withLock {
            val current = readVersionsLocked(draftId)
            val latest = current.firstOrNull()
            if (source != DraftVersionSource.RESTORED && latest != null && latest.text == text) {
                return@withLock latest
            }

            val nextNumber = (current.maxOfOrNull { it.versionNumber } ?: 0) + 1
            val newVersion = DraftVersion(
                id = UUID.randomUUID().toString(),
                draftId = draftId,
                versionNumber = nextNumber,
                text = text,
                source = source,
                createdAtMillis = createdAtMillis,
            )
            val updated = (listOf(newVersion) + current).take(maxVersionsPerDraft)
            writeVersionsLocked(draftId, updated)
            newVersion
        }
        changeNotifications.tryEmit(draftId)
        recorded
    }

    override suspend fun clearVersions(draftId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val target = versionFile(draftId)
            if (target.exists()) {
                target.delete()
            }
        }
        changeNotifications.tryEmit(draftId)
        Unit
    }

    private fun readVersionsLocked(draftId: String): List<DraftVersion> {
        val file = versionFile(draftId)
        if (!file.exists()) return emptyList()
        return try {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString<List<DraftVersion>>(raw)
                    .sortedByDescending { it.versionNumber }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeVersionsLocked(draftId: String, versions: List<DraftVersion>) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val target = versionFile(draftId)
        val temp = File(baseDir, "${target.name}.tmp")
        temp.writeText(json.encodeToString(versions), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            // Trên một số hệ thống tệp, thử ghi đè trực tiếp nếu rename/delete bị chặn tạm thời.
            target.writeText(json.encodeToString(versions), Charsets.UTF_8)
            temp.delete()
            return
        }
        if (!temp.renameTo(target)) {
            target.writeText(json.encodeToString(versions), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun versionFile(draftId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(draftId.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(baseDir, "$hash.json")
    }

    companion object {
        const val DIRECTORY_NAME = "draft_versions"
        const val MAX_VERSIONS_PER_DRAFT = 20
    }
}
