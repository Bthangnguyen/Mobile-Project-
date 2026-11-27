package com.lttbdd.socialagent.feature.review.hook

/**
 * Tách và thay câu mở đầu (hook) của bài viết (F4, Issue #63).
 *
 * Câu mở đầu là **dòng đầu tiên không rỗng** của bài, tính đến ký tự xuống dòng đầu tiên.
 * Các hàm ở đây thuần và xác định (không gọi LLM), nhờ vậy phần còn lại của bài được giữ nguyên từng ký tự.
 */
object OpeningLine {

    /** Câu mở đầu đã bỏ khoảng trắng hai đầu, hoặc `null` khi bài rỗng. */
    fun find(text: String): String? =
        locate(text)?.let { span -> text.substring(span.contentStart, span.contentEnd) }

    /**
     * Thay câu mở đầu của [text] bằng [newOpening] (đã bỏ khoảng trắng hai đầu).
     *
     * - Bài nhiều dòng: chỉ thay phần chữ của dòng mở đầu; khoảng trắng đầu/cuối dòng đó và mọi dòng khác giữ nguyên.
     * - Bài chỉ có một dòng: chèn [newOpening] thành dòng đầu, cách một dòng trống, nội dung cũ giữ nguyên bên dưới.
     * - Bài rỗng: trả về [newOpening].
     */
    fun replace(text: String, newOpening: String): String {
        val opening = newOpening.trim()
        val span = locate(text) ?: return opening
        val hasContentAfterOpening = text.substring(span.lineEnd).isNotBlank()
        return if (hasContentAfterOpening) {
            text.substring(0, span.contentStart) + opening + text.substring(span.contentEnd)
        } else {
            text.substring(0, span.lineStart) + opening + SINGLE_LINE_SEPARATOR + text.substring(span.lineStart)
        }
    }

    /** Vị trí dòng mở đầu trong bài: [lineStart, lineEnd) là cả dòng, [contentStart, contentEnd) là phần chữ. */
    private data class Span(
        val lineStart: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val lineEnd: Int,
    )

    private fun locate(text: String): Span? {
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) text.length else newline
            val line = text.substring(lineStart, lineEnd)
            if (line.isNotBlank()) {
                val first = line.indexOfFirst { !it.isWhitespace() }
                val last = line.indexOfLast { !it.isWhitespace() }
                return Span(
                    lineStart = lineStart,
                    contentStart = lineStart + first,
                    contentEnd = lineStart + last + 1,
                    lineEnd = lineEnd,
                )
            }
            if (newline == -1) return null
            lineStart = newline + 1
        }
        return null
    }

    private const val SINGLE_LINE_SEPARATOR = "\n\n"
}
