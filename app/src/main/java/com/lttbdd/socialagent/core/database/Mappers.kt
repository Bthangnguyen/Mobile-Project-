package com.lttbdd.socialagent.core.database

import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.MetricsSnapshotEntity
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostMetrics

// Chuyển giữa entity của Room và model trong core/model, để code ngoài DB chỉ làm việc với model.

fun DraftEntity.toModel() = Draft(
    id = id,
    topicId = topicId,
    channel = channel,
    text = text,
    imageUri = imageUri,
    imageAltText = imageAltText,
    approved = approved,
)

fun Draft.toEntity(updatedAtMillis: Long) = DraftEntity(
    id = id,
    topicId = topicId,
    channel = channel,
    text = text,
    imageUri = imageUri,
    imageAltText = imageAltText,
    approved = approved,
    updatedAtMillis = updatedAtMillis,
)

fun CommentEntity.toModel() = Comment(
    id = id,
    channel = channel,
    remotePostId = remotePostId,
    authorName = authorName,
    text = text,
    createdAtMillis = createdAtMillis,
    category = category,
    status = status,
)

fun Comment.toEntity() = CommentEntity(
    id = id,
    channel = channel,
    remotePostId = remotePostId,
    authorName = authorName,
    text = text,
    createdAtMillis = createdAtMillis,
    category = category,
    status = status,
)

fun MetricsSnapshotEntity.toModel() = PostMetrics(
    channel = channel,
    remotePostId = remotePostId,
    likes = likes,
    comments = comments,
    shares = shares,
    impressions = impressions,
    capturedAtMillis = capturedAtMillis,
)

fun PostMetrics.toEntity() = MetricsSnapshotEntity(
    channel = channel,
    remotePostId = remotePostId,
    likes = likes,
    comments = comments,
    shares = shares,
    impressions = impressions,
    capturedAtMillis = capturedAtMillis,
)
