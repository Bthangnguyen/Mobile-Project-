package com.lttbdd.socialagent.data.ai

/** Mã lỗi nội bộ để tầng UI ánh xạ resource; không chứa body, URL, prompt hoặc key. */
enum class LlmFailure {
    MISSING_CONFIGURATION,
    MISSING_API_KEY,
    UNSUPPORTED_PROVIDER,
    UNSUPPORTED_TASK,
    INVALID_REQUEST,
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMIT,
    SERVER,
    HTTP,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE,
    INCOMPLETE_RESPONSE,
    CONTENT_BLOCKED,
    EMPTY_RESPONSE,
    PROVIDER,
}

/** Không đính kèm exception gốc vì nhà cung cấp/transport có thể đưa key vào message. */
class LlmException(
    val failure: LlmFailure,
    val httpStatus: Int? = null,
) : Exception(failure.name)

internal fun httpFailure(status: Int): LlmException = LlmException(
    failure = when (status) {
        401 -> LlmFailure.AUTHENTICATION
        403 -> LlmFailure.PERMISSION
        429 -> LlmFailure.RATE_LIMIT
        in 500..599 -> LlmFailure.SERVER
        else -> LlmFailure.HTTP
    },
    httpStatus = status,
)
