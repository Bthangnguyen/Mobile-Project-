package com.lttbdd.socialagent.feature.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.database.dao.CommentPostKey
import com.lttbdd.socialagent.core.database.dao.CommentPostPreview
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.data.comments.CommentInboxFilter
import com.lttbdd.socialagent.data.comments.CommentInboxRepository
import com.lttbdd.socialagent.data.comments.CommentPageCursor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update

data class CommentInboxState(
    val filter: CommentInboxFilter = CommentInboxFilter(),
    val posts: List<CommentPostPreview> = emptyList(),
    val comments: List<Comment> = emptyList(),
    val isLoading: Boolean = true,
    internal val after: CommentPageCursor? = null,
    val hasMore: Boolean = false,
    val hasError: Boolean = false,
) {
    val hasPrevious: Boolean get() = after != null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommentInboxViewModel @Inject constructor(repository: CommentInboxRepository) : ViewModel() {
    private val request = MutableStateFlow(InboxRequest())

    private val retry = MutableStateFlow(0)

    val uiState: StateFlow<CommentInboxState> = retry.flatMapLatest {
        val posts = request.map { it.filter.channel }.distinctUntilChanged().flatMapLatest { channel ->
            repository.observePostPreviews(channel).map { channel to it }
        }
        val pages = request.flatMapLatest { query ->
            repository.observeComments(query.filter, PAGE_SIZE + 1, query.after)
                .transform { comments ->
                    if (query.after != null && comments.isEmpty()) {
                        // Về trang đầu nếu dữ liệu thay đổi và trang hiện tại biến mất.
                        request.update { if (it == query) it.copy(cursors = emptyList()) else it }
                    } else {
                        emit(CommentInboxState(
                            filter = query.filter,
                            comments = comments.take(PAGE_SIZE),
                            after = query.after,
                            isLoading = false,
                            hasMore = comments.size > PAGE_SIZE,
                        ))
                    }
                }.onStart {
                    emit(CommentInboxState(filter = query.filter, after = query.after))
                }
        }
        combine(posts, pages) { (postChannel, previews), page ->
            if (postChannel == page.filter.channel) page.copy(posts = previews)
            else CommentInboxState(filter = page.filter, after = page.after)
        }.onStart {
            emit(CommentInboxState(filter = request.value.filter, after = request.value.after))
        }.catch {
            val query = request.value
            emit(CommentInboxState(filter = query.filter, after = query.after, isLoading = false, hasError = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommentInboxState())

    fun selectChannel(channel: ChannelType?) = updateFilter {
        copy(channel = channel, post = post?.takeIf { channel == null || it.channel == channel })
    }

    fun selectPost(post: CommentPostKey?) = updateFilter {
        if (post == null || channel == null || post.channel == channel) copy(post = post) else this
    }

    fun selectStatus(status: CommentStatus?) = updateFilter { copy(status = status) }

    fun clearFilters() = updateFilter { CommentInboxFilter() }

    fun nextPage() {
        val current = uiState.value
        if (!current.hasMore) return
        val lastComment = current.comments.lastOrNull() ?: return
        movePage(current) { this + CommentPageCursor(lastComment) }
    }

    fun previousPage() {
        val current = uiState.value
        if (!current.hasPrevious) return
        movePage(current) { dropLast(1) }
    }

    private fun movePage(
        current: CommentInboxState,
        updateCursors: List<CommentPageCursor>.() -> List<CommentPageCursor>,
    ) {
        if (current.isLoading || current.hasError) return
        request.update {
            if (it.filter == current.filter && it.after == current.after) {
                it.copy(cursors = it.cursors.updateCursors())
            } else {
                it
            }
        }
    }

    fun retry() = retry.update { it + 1 }

    private fun updateFilter(transform: CommentInboxFilter.() -> CommentInboxFilter) {
        request.update {
            val updated = it.filter.transform()
            if (updated == it.filter) it else InboxRequest(filter = updated)
        }
    }

    private data class InboxRequest(
        val filter: CommentInboxFilter = CommentInboxFilter(),
        val cursors: List<CommentPageCursor> = emptyList(),
    ) {
        val after: CommentPageCursor? get() = cursors.lastOrNull()
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
