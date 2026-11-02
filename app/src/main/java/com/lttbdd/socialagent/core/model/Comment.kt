package com.lttbdd.socialagent.core.model

/** Nhóm phân loại comment của AI (F6). */
enum class CommentCategory { QUESTION, PRAISE, FEEDBACK, NEGATIVE, SPAM }

enum class CommentStatus { NEW, REPLIED, IGNORED }

data class Comment(
    val id: String,
    val channel: ChannelType,
    val remotePostId: String,
    val authorName: String,
    val text: String,
    val createdAtMillis: Long,
    val category: CommentCategory? = null,
    val status: CommentStatus = CommentStatus.NEW,
)
