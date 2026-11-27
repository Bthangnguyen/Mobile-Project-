package com.lttbdd.socialagent.data.comments

import com.lttbdd.socialagent.core.database.dao.CommentDao
import com.lttbdd.socialagent.core.database.dao.CommentPostKey
import com.lttbdd.socialagent.core.database.dao.CommentPostPreview
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class CommentInboxFilter(
    val channel: ChannelType? = null,
    val post: CommentPostKey? = null,
    val status: CommentStatus? = null,
)

data class CommentPageCursor(val createdAtMillis: Long, val channel: ChannelType, val id: String) {
    constructor(comment: Comment) : this(comment.createdAtMillis, comment.channel, comment.id)
}

class CommentInboxRepository @Inject constructor(private val dao: CommentDao) {
    fun observeComments(
        filter: CommentInboxFilter,
        limit: Int,
        after: CommentPageCursor? = null,
    ): Flow<List<Comment>> {
        if (filter.channel != null && filter.post != null && filter.channel != filter.post.channel) {
            return flowOf(emptyList())
        }
        return dao.observePageAfter(
            filter.post?.channel ?: filter.channel,
            filter.post?.remotePostId,
            filter.status,
            limit,
            after?.createdAtMillis,
            after?.channel,
            after?.id,
        ).distinctUntilChanged().map { entries -> entries.map { it.toModel() } }
    }

    fun observePostPreviews(channel: ChannelType?): Flow<List<CommentPostPreview>> =
        dao.observePostPreviews(channel).distinctUntilChanged()
}
