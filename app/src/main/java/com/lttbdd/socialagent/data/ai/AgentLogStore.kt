package com.lttbdd.socialagent.data.ai

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

/**
 * Kho lưu trữ ngoại tuyến cho nhật ký chạy AI agent (F2, Issue #31).
 * Lưu bằng tệp JSON nguyên tử riêng để không làm thay đổi schema `AppDatabase` trong `core/`.
 */
interface AgentLogStore {

    /** Quan sát danh sách nhật ký các lượt chạy theo thứ tự mới nhất trước. */
    fun observeLogs(): Flow<List<AgentRunLog>>

    /** Đọc danh sách nhật ký hiện có (mới nhất trước). */
    suspend fun getLogs(): List<AgentRunLog>

    /** Ghi nhận một lượt chạy agent (luôn được làm sạch bí mật trước khi lưu). */
    suspend fun recordRun(log: AgentRunLog)

    /** Xóa toàn bộ nhật ký agent trên thiết bị. */
    suspend fun clearAll()
}

/** Triển khai rỗng dùng mặc định cho unit test không cần lưu nhật ký. */
object NoOpAgentLogStore : AgentLogStore {
    override fun observeLogs(): Flow<List<AgentRunLog>> = emptyFlow()
    override suspend fun getLogs(): List<AgentRunLog> = emptyList()
    override suspend fun recordRun(log: AgentRunLog) = Unit
    override suspend fun clearAll() = Unit
}

/**
 * Lưu nhật ký agent ra tệp JSON nguyên tử (`filesDir/agent_logs/agent_runs.json`),
 * có cơ chế sao lưu `.bak` + `fsync` để không mất dữ liệu khi app dừng giữa chừng và giới hạn [maxRuns] lượt gần nhất.
 */
class FileAgentLogStore(
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxRuns: Int = MAX_RUN_LOGS,
) : AgentLogStore {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val revision = MutableStateFlow(0L)

    override fun observeLogs(): Flow<List<AgentRunLog>> =
        revision
            .map { getLogs() }
            .distinctUntilChanged()

    override suspend fun getLogs(): List<AgentRunLog> = withContext(ioDispatcher) {
        mutex.withLock {
            readLogsLocked()
        }
    }

    override suspend fun recordRun(log: AgentRunLog) = withContext(ioDispatcher) {
        val sanitized = AgentLogSanitizer.sanitizeRunLog(log)
        mutex.withLock {
            val current = readLogsLocked().filterNot { it.id == sanitized.id }
            val updated = (listOf(sanitized) + current)
                .sortedByDescending { it.startedAtMillis }
                .take(maxRuns)
            writeLogsAtomicallyLocked(updated)
            revision.update { it + 1 }
        }
    }

    override suspend fun clearAll() = withContext(ioDispatcher) {
        mutex.withLock {
            val target = logFile()
            val backup = backupFile(target)
            if (target.exists()) target.delete()
            if (backup.exists()) backup.delete()
            revision.update { it + 1 }
        }
    }

    private fun readLogsLocked(): List<AgentRunLog> {
        val target = logFile()
        val backup = backupFile(target)
        if (!target.exists() && backup.exists()) {
            backup.renameTo(target)
        }
        val primary = parseFile(target)
        if (primary != null) return primary
        if (backup.exists()) {
            val recovered = parseFile(backup)
            if (recovered != null) return recovered
        }
        return emptyList()
    }

    private fun parseFile(file: File): List<AgentRunLog>? {
        if (!file.exists()) return null
        return try {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString<List<AgentRunLog>>(raw)
                    .sortedByDescending { it.startedAtMillis }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLogsAtomicallyLocked(logs: List<AgentRunLog>) {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw IOException("Không thể tạo thư mục lưu nhật ký agent: ${baseDir.name}")
        }
        val target = logFile()
        val backup = backupFile(target)
        val temp = File(baseDir, "${target.name}.new")
        val payload = json.encodeToString(logs).toByteArray(Charsets.UTF_8)

        try {
            FileOutputStream(temp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            if (target.exists()) {
                if (backup.exists() && !backup.delete()) {
                    throw IOException("Không thể dọn tệp sao lưu nhật ký cũ")
                }
                if (!target.renameTo(backup)) {
                    throw IOException("Không thể sao lưu tệp nhật ký trước khi thay thế")
                }
            }
            if (!temp.renameTo(target)) {
                if (backup.exists() && !target.exists()) {
                    backup.renameTo(target)
                }
                throw IOException("Không thể thay thế tệp nhật ký một cách nguyên tử")
            }
            if (backup.exists()) {
                backup.delete()
            }
        } catch (e: Exception) {
            if (temp.exists()) temp.delete()
            throw e
        }
    }

    private fun logFile(): File = File(baseDir, FILE_NAME)
    private fun backupFile(target: File): File = File(baseDir, "${target.name}.bak")

    companion object {
        const val DIRECTORY_NAME = "agent_logs"
        private const val FILE_NAME = "agent_runs.json"
        const val MAX_RUN_LOGS = 50
    }
}
