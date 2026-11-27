package com.lttbdd.socialagent.data.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
 * - Ghi an toàn theo cơ chế backup-trước-khi-thay-thế (tương tự `AtomicFile`): ghi và `fsync` ra tệp `.new`,
 *   sao lưu tệp cũ sang `.bak`, đổi tên `.new` → tệp chính rồi mới xóa `.bak`; khi đọc có thể phục hồi từ `.bak`
 *   nếu quá trình ghi trước bị ngắt giữa chừng. Không bao giờ xóa tệp chính trước khi sao lưu hoặc ghi đè trực tiếp.
 * - Quan sát thay đổi qua [MutableStateFlow] đơn điệu (`revision`) + `distinctUntilChanged()` để không bỏ lỡ
 *   bản ghi xảy ra giữa lúc đọc ban đầu và lúc đăng ký lắng nghe.
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
    private val revision = MutableStateFlow(0L)

    override fun observeVersions(draftId: String): Flow<List<DraftVersion>> =
        revision
            .map { getVersions(draftId) }
            .distinctUntilChanged()

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
        mutex.withLock {
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
            writeVersionsAtomicallyLocked(draftId, updated)
            revision.update { it + 1 }
            newVersion
        }
    }

    override suspend fun clearVersions(draftId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val target = versionFile(draftId)
            val backup = backupFile(target)
            if (target.exists()) target.delete()
            if (backup.exists()) backup.delete()
            revision.update { it + 1 }
        }
    }

    private fun readVersionsLocked(draftId: String): List<DraftVersion> {
        val target = versionFile(draftId)
        val backup = backupFile(target)
        // Phục hồi từ bản sao lưu nếu lần ghi trước bị ngắt sau khi đã chuyển tệp chính sang .bak
        if (!target.exists() && backup.exists()) {
            backup.renameTo(target)
        }
        val primary = parseVersionsFile(target)
        if (primary != null) return primary
        if (backup.exists()) {
            val recovered = parseVersionsFile(backup)
            if (recovered != null) return recovered
        }
        return emptyList()
    }

    private fun parseVersionsFile(file: File): List<DraftVersion>? {
        if (!file.exists()) return null
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
            null
        }
    }

    private fun writeVersionsAtomicallyLocked(draftId: String, versions: List<DraftVersion>) {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw IOException("Không thể tạo thư mục lưu lịch sử phiên bản: ${baseDir.name}")
        }
        val target = versionFile(draftId)
        val backup = backupFile(target)
        val temp = File(baseDir, "${target.name}.new")
        val payload = json.encodeToString(versions).toByteArray(Charsets.UTF_8)

        try {
            FileOutputStream(temp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            if (target.exists()) {
                if (backup.exists() && !backup.delete()) {
                    throw IOException("Không thể dọn tệp sao lưu cũ")
                }
                if (!target.renameTo(backup)) {
                    throw IOException("Không thể sao lưu tệp lịch sử trước khi thay thế")
                }
            }
            if (!temp.renameTo(target)) {
                // Khôi phục lại bản sao lưu nếu bước đổi tên tệp mới thất bại
                if (backup.exists() && !target.exists()) {
                    backup.renameTo(target)
                }
                throw IOException("Không thể thay thế tệp lịch sử một cách nguyên tử")
            }
            if (backup.exists()) {
                backup.delete()
            }
        } catch (e: Exception) {
            if (temp.exists()) temp.delete()
            throw e
        }
    }

    private fun versionFile(draftId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(draftId.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(baseDir, "$hash.json")
    }

    private fun backupFile(target: File): File = File(baseDir, "${target.name}.bak")

    companion object {
        const val DIRECTORY_NAME = "draft_versions"
        const val MAX_VERSIONS_PER_DRAFT = 20
    }
}
