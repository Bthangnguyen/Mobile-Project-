package com.lttbdd.socialagent.data.apikeys

import android.util.AtomicFile
import com.lttbdd.socialagent.core.model.LlmProvider
import java.io.File
import java.io.IOException

/** Thư mục riêng của ứng dụng; backup rules loại trừ cả thư mục và file phụ của AtomicFile. */
class FileCiphertextStorage(private val directory: File) : CiphertextStorage {
    private fun file(provider: LlmProvider): File {
        requireKeyProvider(provider)
        return File(directory, "${provider.name}.bin")
    }

    override fun read(provider: LlmProvider): ByteArray? {
        val file = file(provider)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().use { stream ->
            val record = stream.readBytesBounded(AesGcmApiKeyCipher.MAX_RECORD_SIZE)
            if (record.isEmpty()) throw IOException("EMPTY_RECORD")
            record
        }
    }

    override fun write(provider: LlmProvider, record: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("STORAGE_UNAVAILABLE")
        val atomic = AtomicFile(file(provider))
        val stream = atomic.startWrite()
        try {
            stream.write(record)
            atomic.finishWrite(stream)
            // AtomicFile có thể chỉ log lỗi fsync/rename; xác nhận bản ghi trước khi báo đã lưu.
            if (read(provider)?.contentEquals(record) != true) throw IOException("WRITE_NOT_COMMITTED")
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override fun delete(provider: LlmProvider) {
        val file = file(provider)
        AtomicFile(file).delete()
        if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any { it.exists() }) {
            throw IOException("DELETE_FAILED")
        }
    }

    companion object {
        const val DIRECTORY = "llm_api_keys"
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(4096)
    while (true) {
        val count = read(chunk)
        if (count == -1) break
        if (buffer.size() + count > limit) throw IOException("RECORD_TOO_LARGE")
        buffer.write(chunk, 0, count)
    }
    return buffer.toByteArray()
}
