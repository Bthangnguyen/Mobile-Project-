# Research LLM Provider & Kiến trúc AI Agent cho Social Agent (F2/F8)

> **Issue:** [#3 — Research LLM provider và kiến trúc agent](https://github.com/Bthangnguyen/Mobile-Project-/issues/3)  
> **Người phụ trách:** C (`sangcotdo2407`) · **Milestone:** M1  
> **Ngày tra cứu:** 02/11/2026 · **Loại tài liệu:** Nghiên cứu và đề xuất, **chưa phải implementation**.  
> **Phạm vi:** F2 (AI Agent viết bài/sinh ảnh), F8 (Bring Your Own Key — BYOK), một số điểm tích hợp F4/F6/F9.

## 1. Tóm tắt kết luận

1. **Đề xuất hỗ trợ trước: Gemini và OpenAI.** Gemini 2.5 Flash-Lite phù hợp để thử nghiệm và phân loại comment nhờ giá thấp, có free tier tùy giới hạn; OpenAI có API streaming và hệ sinh thái sinh ảnh riêng. Cả hai đều phù hợp cơ chế BYOK. **Đây là đề xuất thiết kế, chưa có benchmark hoặc thử bằng API key thực tế**.
2. **Provider khác:** Anthropic là phương án mở rộng cho tác vụ viết chất lượng cao (không có API sinh ảnh gốc tương đương); OpenRouter hỗ trợ nhiều model qua một API nhưng chi phí/chính sách phụ thuộc model và tuyến nhà cung cấp.
3. **F2:** `ContentAgent` làm orchestrator; với mỗi `ChannelType`, tạo một coroutine con; truyền tiến độ qua `Flow<AgentEvent>`. Một kênh lỗi chỉ phát `ChannelFailed`, không hủy kênh còn lại; hủy collect Flow phải hủy toàn bộ network requests tương ứng.
4. **F8:** Người dùng chọn provider/model theo `LlmTask`; kiểm tra key qua endpoint metadata ít rủi ro; mã hóa key bằng khóa trong Android Keystore và **không gửi đến server của nhóm**.
5. **Chi phí:** Lấy số token chính thức từ response cuối nếu có, lưu thống kê từng bước/kênh/ngày/tháng; tính tiền theo bảng giá gắn với provider + model + thời điểm. Chi phí ảnh và các dịch vụ ngoài token phải được ghi riêng.
6. **Không sửa `core/ai` trong Issue #3.** Mọi thay đổi hợp đồng chung phải được trao đổi qua Issue có nhãn `contract` theo `AGENTS.md`/`docs/architecture.md`.

## 2. Yêu cầu và ràng buộc của dự án

- **F2 bắt buộc:** Brief chung; mỗi kênh một sub-agent chạy song song; luồng `OUTLINE → DRAFT → SELF_REVIEW → IMAGE_PROMPT → IMAGE → PACKAGE`; streaming, tiến độ, hủy toàn bộ, chạy lại một kênh, lỗi độc lập, nhật ký prompt/tokens/chi phí.
- **F8 bắt buộc:** Ít nhất hai LLM provider; xác thực key; lưu key an toàn và hiển thị che; chọn model theo tác vụ viết bài/phân loại comment/sinh ảnh; thống kê chi phí theo ngày/tháng.
- **F4:** Người dùng phải duyệt trước khi đăng; **AI Agent không tự đăng**.
- **F9:** Khi offline vẫn xem được dữ liệu đã lưu; không mặc định suy ra có thể gọi API LLM khi offline.
- **Kiến trúc nhóm:** Kotlin, XML + ViewBinding, MVVM, Hilt, Coroutines/Flow, Room; UI → ViewModel → Repository/Client; không import implementation của mảng khác và không thay chữ ký `core/` khi chưa được phê duyệt.

Nguồn nội bộ: [Issue #3](https://github.com/Bthangnguyen/Mobile-Project-/issues/3), [`docs/architecture.md`](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/docs/architecture.md), [`core/ai/ContentAgent.kt`](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/ai/ContentAgent.kt), [`core/ai/LlmClient.kt`](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/ai/LlmClient.kt), [`core/model/Agent.kt`](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/model/Agent.kt) và [`core/model/Llm.kt`](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/model/Llm.kt).

## 3. So sánh nhà cung cấp LLM

### 3.1. Bảng giá, model, tính năng

**Đơn vị giá text:** USD / **1 triệu token**, mức trả theo lượt gọi chuẩn (không tính caching/batch/giảm giá/tax). Giá được tra cứu ngày 02/11/2026, **có thể thay đổi**, cần xác minh lại trước khi tích hợp và trước khi hiển thị cho người dùng. *Free tier không đồng nghĩa với không giới hạn hoặc có sẵn ở mọi vùng/tài khoản.*

| Provider | Model đề xuất viết bài | Model đề xuất phân loại comment | Giá text Input / Output (USD/1M token) | Free tier API | Stream text (SSE) | Sinh ảnh qua API |
|---|---|---|---|---|---|---|
| **Google Gemini** | `gemini-2.5-flash` khi cần chất lượng; `gemini-2.5-flash-lite` cho chi phí thấp | `gemini-2.5-flash-lite` | **Flash-Lite: $0.10 / $0.40** | **Có** với Flash-Lite theo hạn mức/rate limit hiện hành | **Có**, `streamGenerateContent`/SSE | **Có**, dùng model ảnh riêng, ví dụ `gemini-3.1-flash-lite-image` |
| **OpenAI** | `gpt-6.1-terra` khi cần nâng chất lượng; `gpt-6-luna` khi tiết kiệm | `gpt-6-luna` | **Luna: $0.10 / $0.50**; Terra: $1.00 / $5.00 (Standard) | Không mặc định có free tier API thường xuyên; tùy credits/ưu đãi | **Có**, Responses API `stream=true` | **Có**, dòng `gpt-image-*` qua API ảnh (tính giá riêng) |
| **Anthropic** | `claude-sonnet-4-5` hoặc model Sonnet đang được hỗ trợ | `claude-haiku-4-5` | **Haiku 4.5: $1.00 / $5.00**; Sonnet 4.5: $3.00 / $15.00 | Không nên giả định có free tier API; tùy credits/tài khoản | **Có**, Messages `stream=true` | **Không có API sinh ảnh gốc tương đương**; kết hợp provider ảnh khác/template |
| **OpenRouter** | Chọn model text theo danh mục (vd. tuyến Gemini/OpenAI/nhà cung cấp khác) | Model rẻ hoặc model `:free` nếu phù hợp | **Thay đổi theo model & tuyến**; không có một mức giá chung cho toàn OpenRouter | Có model `:free`, **có giới hạn** | **Có**, chat completions `stream=true` | **Phụ thuộc model/endpoint**; không mặc định mọi model đều sinh được ảnh |

**Ghi chú quan trọng:**

- Giá sinh ảnh **không** được hiểu là giá text ở bảng trên. Ví dụ Google công bố `gemini-3.1-flash-lite-image` khoảng **$0.0336/ảnh 1K** trên standard tier (chưa tính những đầu vào khác); OpenAI dùng cách tính token ảnh theo model/chất lượng/độ phân giải, phải dùng tài liệu giá ảnh khi gọi thực tế.
- Một model **nhận ảnh để hiểu** chưa chắc **sinh ảnh** được. Phải kiểm tra riêng khả năng *image output* và quyền API model.
- OpenRouter có thể định tuyến qua bên thứ ba: phải nêu rõ chính sách dữ liệu, mức giá, độ sẵn có, quota và model ID trước khi bật.
- Free model OpenRouter có rate limit (theo tài liệu: các biến thể `:free` giới hạn số request/phút/ngày tùy điều kiện tài khoản); không dùng làm cam kết SLA.
- Giá ở đây là **giá niêm yết tại thời điểm nghiên cứu**, không phải hóa đơn hay kết quả test tốc độ/chất lượng của nhóm.

**Nguồn giá và tính năng:** [OpenAI Pricing](https://developers.openai.com/api/docs/pricing), [OpenAI Streaming](https://developers.openai.com/api/docs/guides/streaming-responses), [Gemini Pricing](https://ai.google.dev/gemini-api/docs/pricing), [Gemini API Reference](https://ai.google.dev/api/models), [Anthropic Pricing](https://platform.claude.com/docs/en/about-claude/pricing), [Anthropic Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming), [OpenRouter Free Models](https://openrouter.ai/collections/free-models), [OpenRouter Support](https://openrouter.ai/support).

### 3.2. Đề xuất lựa chọn cho M2

**Ưu tiên số 1 — Gemini:** phù hợp làm provider đầu tiên cho MVP vì `gemini-2.5-flash-lite` có mức giá thấp và free tier có giới hạn cho tác vụ text; có thể tích hợp thêm endpoint sinh ảnh Gemini khi có quyền truy cập. Trước demo cần thử quota, độ ổn định, tiếng Việt và tính sẵn có của model sinh ảnh.

**Ưu tiên số 2 — OpenAI:** cung cấp provider thứ hai độc lập để đáp ứng yêu cầu F8, có streaming text và API ảnh. Cho phép người dùng chọn model và tự chịu phí bằng BYOK; **gói ChatGPT không đồng nghĩa với tín dụng OpenAI API**.

**Chưa ưu tiên:** Anthropic (phù hợp mở rộng năng lực viết, nhưng cần thêm nhà cung cấp để sinh ảnh); OpenRouter (giảm số lượng adapter cho nhiều model về sau nhưng thêm phụ thuộc tuyến và chính sách bên thứ ba). Khi đánh giá thật, benchmark tối thiểu 10 chủ đề VI/EN với tiêu chí: đúng brief, tuân thủ giới hạn độ dài, độ trễ chữ đầu tiên, độ ổn định, chi phí.

**Ánh xạ `LlmTask` ban đầu:**

| `LlmTask` | Gemini (mặc định gợi ý) | OpenAI (tùy chọn) | Fallback |
|---|---|---|---|
| `WRITE_POST` | `gemini-2.5-flash` hoặc Flash-Lite | `gpt-6-luna` / `gpt-6.1-terra` | Giữ bản nháp cũ; người dùng tự thử lại |
| `CLASSIFY_COMMENT` | `gemini-2.5-flash-lite` | `gpt-6-luna` | Chưa phân loại, không tự động gửi trả lời |
| `GENERATE_IMAGE` | `gemini-3.1-flash-lite-image` nếu được cấp quyền | `gpt-image-*` tương thích nếu được cấp quyền | Template offline (mục 8) |

Các ID là **cấu hình đề xuất**, cần kiểm tra qua API `models`/danh mục model tại thời điểm chạy; ứng dụng không hard-code duy nhất một model.

## 4. Kiểm tra API key (F8)

Mục đích của thao tác *Validate key* là kiểm tra sơ bộ việc xác thực **không tiêu thụ token sinh nội dung nếu có thể**. Endpoint metadata thường không có phí token inference, **nhưng kết quả 200 không bảo đảm** tài khoản có billing, quota, hoặc quyền dùng mọi model. Có thể thêm nút “Test model” riêng, kèm cảnh báo **có thể phát sinh phí**.

| Provider | Endpoint kiểm tra sơ bộ | Header xác thực | Giá kiểm tra metadata | Lưu ý |
|---|---|---|---|---|
| OpenAI | `GET https://api.openai.com/v1/models` | `Authorization: Bearer <KEY>` | Không tính token sinh nội dung | 200 chỉ chứng tỏ key có thể đọc model list; model cụ thể có thể bị hạn chế |
| Gemini | `GET https://generativelanguage.googleapis.com/v1beta/models?pageSize=1` | `x-goog-api-key: <KEY>` | Không phát sinh token inference | Kiểm tra quota, model, API key restrictions riêng; không đưa key vào query string/URL để tránh log |
| Anthropic | `GET https://api.anthropic.com/v1/models` | `x-api-key: <KEY>` và `anthropic-version: 2023-06-01` | Không tính token sinh nội dung | Lỗi 401/403/429 phải phân biệt, không mặc định tất cả là key sai |
| OpenRouter | `GET https://openrouter.ai/api/v1/key` | `Authorization: Bearer <KEY>` | Không tính token sinh nội dung | Có thể trả thêm trạng thái limit/balance; model `:free` có quota riêng |

**Luồng UI khuyến nghị:**

1. Người dùng chọn provider → nhập key (ô password) → nhấn **Kiểm tra kết nối**; không tự gửi key khi gõ.
2. Gửi request HTTPS trực tiếp từ Android tới đúng domain provider; không đi qua backend nhóm, không gửi sang analytics/crash log.
3. Phân loại kết quả: `VALID`, `INVALID_AUTH` (401), `FORBIDDEN_OR_RESTRICTED` (403), `RATE_LIMITED` (429), `NETWORK_ERROR`, `UNKNOWN_ERROR`. Không coi mất mạng/timeout là key sai.
4. Với key hợp lệ, mã hóa và lưu **ciphertext** trong bộ nhớ riêng ứng dụng, khóa mã hóa giữ trong **Android Keystore**; hiển thị kiểu `••••…1234`, không hiện key trong log, exception, screenshots debug, backup, PR hoặc Room database dạng plaintext.
5. Cho phép thay/thu hồi key; xóa ciphertext khi người dùng yêu cầu. Key được giải mã tạm thời chỉ trước khi gọi API và không đưa ra `UiState`/log.
6. Trước một lệnh tốn phí, kiểm tra model còn khả dụng và xác nhận nếu chi phí dự kiến vượt ngưỡng cảnh báo người dùng đặt.

**Lưu ý về an toàn:** Android Keystore lưu **khóa mã hóa không xuất được**, không phải nơi trực tiếp lưu nguyên chuỗi API key; cần kết hợp mã hóa dữ liệu nhạy cảm tại rest. Máy người dùng bị chiếm quyền vẫn có rủi ro; BYOK chỉ giảm việc phải lưu bí mật trên backend của nhóm, không biến API key thành không thể lộ.

Nguồn: [OpenAI List Models](https://developers.openai.com/api/reference/resources/models/methods/list), [Gemini API Key](https://ai.google.dev/gemini-api/docs/api-key), [Gemini Models](https://ai.google.dev/api/models), [Anthropic List Models](https://platform.claude.com/docs/en/api/models/list), [OpenRouter API Key & Limits](https://openrouter.ai/docs/api/reference/limits), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## 5. Thiết kế Orchestrator + Sub-agent (F2)

### 5.1. Luồng xử lý và phân tầng

```text
TopicFragment (F3, XML)
       ↓ user nhấn “Tạo nội dung”
TopicViewModel / AgentViewModel (StateFlow<UiState>)
       ↓ collect ContentAgent.generate(TopicBrief)
ContentAgentImpl (data/ai/, orchestrator)
       ├── brief chung → AgentEvent.BriefReady
       └── supervisorScope / channelFlow
           ├── Facebook sub-agent (Coroutine A)
           │   OUTLINE → DRAFT → SELF_REVIEW → IMAGE_PROMPT → IMAGE → PACKAGE
           └── LinkedIn sub-agent (Coroutine B)
               OUTLINE → DRAFT → SELF_REVIEW → IMAGE_PROMPT → IMAGE → PACKAGE
                     ↓ AgentEvent stream theo channel
              Room: draft + nhật ký + cost
                     ↓
           ViewModel cập nhật UiState
                     ↓
           Fragment render tiến độ từng kênh
```

**Nguyên tắc:**

- `channelFlow` cho phép nhiều coroutine con phát sự kiện vào một `Flow` an toàn; `supervisorScope` cách ly lỗi con.
- Mỗi sub-agent có trạng thái riêng (`AgentStep`, nội dung draft đang stream, lỗi, tổng usage, URI ảnh). `TextDelta` gửi từng đoạn UI; hạn chế update UI quá dày bằng throttle ở ViewModel.
- **Luồng làm bài bắt buộc đúng thứ tự** 6 bước trong `AgentStep`; prompt tự kiểm tra quy tắc riêng cho Facebook/LinkedIn.
- `CancellationException` **phải throw lại**, tuyệt đối không bắt thành lỗi nghiệp vụ. UI hủy `Job` đang collect để hủy sub-agent + request SSE + gọi API ảnh; không tiếp tục gửi sự kiện sau khi cancel.
- Exception riêng kênh → `ChannelFailed(channel, step, reasonSafe)`; kênh khác vẫn tiếp tục. Không rò token/key hoặc prompt nhạy cảm trong `reasonSafe`.
- `regenerate(brief, channel)` chỉ tạo lại một kênh; không xóa draft đã thành công ở kênh khác. Dùng `runId`/`channelRunId` nội bộ để phân biệt lần chạy, tránh stream cũ ghi đè kết quả mới.
- **Không tự đăng** sau `ChannelDone`; bản nháp phải chuyển sang màn hình duyệt F4. F5 là chức năng riêng chỉ được gọi sau khi người dùng xác nhận.
- Giới hạn đồng thời (`Semaphore` hoặc dispatcher/limit) để tránh rate limit và nhiều request bất ngờ khi sau này tăng số kênh; lỗi 429 retry có backoff + jitter theo nhà cung cấp, không retry vô hạn.

### 5.2. Ánh xạ vào hợp đồng có sẵn

| Hợp đồng/kiểu trong `core/` | Cách sử dụng tại implementation |
|---|---|
| `ContentAgent.generate(TopicBrief): Flow<AgentEvent>` | Orchestrator tổng hợp brief, khởi tạo kênh, stream tiến độ |
| `ContentAgent.regenerate(brief, channel)` | Chạy lại riêng một sub-agent |
| `ContentAgent.rewriteParagraph(...)` | Phục vụ yêu cầu F4, không ghi đè bài đã đăng |
| `ContentAgent.regenerateImage(draft)` | Gọi provider ảnh hoặc template fallback, trả URI ảnh mới |
| `LlmClient.streamChat(ChatRequest): Flow<ChatChunk>` | Adapter provider stream text; chunk cuối gắn `TokenUsage` nếu có |
| `LlmClient.classifyComment(...)` / `draftReply(...)` | Dùng từ mảng F6; tuyệt đối không tự đăng comment ngoài quy tắc F6 |
| `AgentEvent.BriefReady` | Thông báo hoàn thành brief chung |
| `AgentEvent.StepStarted`, `TextDelta`, `StepFinished(TokenUsage)` | Bước bắt đầu, token stream, usage cuối bước |
| `AgentEvent.ChannelDone`, `ChannelFailed` | Hoàn thành/thất bại độc lập theo kênh |
| `LlmProvider`, `LlmTask`, `ChatRequest`, `TokenUsage` | Chọn provider, loại việc, thống kê số token/chi phí |

**Mẫu tư duy Kotlin (minh họa kiến trúc, chưa phải code có thể dán chạy độc lập):**

```kotlin
// Nằm trong ContentAgentImpl (data/ai/), không sửa ContentAgent interface.
// runSingleChannel là hàm riêng sẽ được hiện thực trong F2.
override fun generate(brief: TopicBrief): Flow<AgentEvent> = channelFlow {
    send(AgentEvent.BriefReady(buildSharedBriefSummary(brief)))

    supervisorScope {
        brief.channels.map { channel ->
            launch {
                var currentStep = AgentStep.OUTLINE
                try {
                    runSingleChannel(
                        brief = brief,
                        channel = channel,
                        onStep = { currentStep = it },
                        emitEvent = { event -> send(event) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled // Hủy đúng toàn bộ cây coroutine.
                } catch (error: Exception) {
                    send(
                        AgentEvent.ChannelFailed(
                            channel = channel,
                            step = currentStep,
                            reason = error.toSafeUserMessage(),
                        )
                    )
                }
            }
        }.joinAll()
    }
}
```

Hàm `runSingleChannel`, `buildSharedBriefSummary` và `toSafeUserMessage` ở trên **chỉ là tên hàm thiết kế**. Chúng chưa tồn tại trong repository và không được xem là mã đã implement hay đã được test. Kotlin imports (`channelFlow`, `supervisorScope`, `launch`, `joinAll`, `CancellationException`) phải được bổ sung tại lúc triển khai.

**Thử nghiệm F2 cần có về sau:** hai kênh chạy xen kẽ; A lỗi nhưng B `ChannelDone`; hủy ở bước `DRAFT` không tiếp tục phát event; `regenerate` chỉ có event kênh đích; log tokens/cost đúng và không ghi khóa API.

## 6. Streaming SSE và vòng đời

| Provider | Ví dụ giao thức | Adapter Android cần làm |
|---|---|---|
| OpenAI | `POST /v1/responses`, `stream: true` → các event như `response.output_text.delta`, `response.completed` | Parse event, nối delta, lấy usage khi hoàn thành; đóng request nếu coroutine cancel |
| Gemini | `models/{model}:streamGenerateContent?alt=sse` | Parse nhiều phần/candidate/usage; xử lý partial/finish reason; kiểm tra API version tương thích |
| Anthropic | `POST /v1/messages`, `stream: true` → `content_block_delta`, `message_delta`, `message_stop` | Parse SSE, không cộng dồn số token usage cumulative nhiều lần |
| OpenRouter | `POST /api/v1/chat/completions`, `stream: true` → SSE | Parse format tương thích OpenAI, cẩn thận provider metadata và usage có/không có |

Trong `data/ai/` tạo adapter riêng cho mỗi provider (vd `GeminiClient`, `OpenAiClient`), dùng `OkHttp` trên `Dispatchers.IO` và Flow/cancellation. SSE có thể rớt mạng hoặc xuất event lỗi: không ghi nhận `SUCCESS` khi chưa có kết thúc hợp lệ; giữ draft đang dở dưới trạng thái `INCOMPLETE` nếu muốn người dùng xem lại.

## 7. Theo dõi token và chi phí (F2/F8)

### 7.1. Công thức

Với text (không caching/tool/image):

```text
costUsd = inputTokens / 1_000_000 × inputPricePerMillion
        + outputTokens / 1_000_000 × outputPricePerMillion
```

**Ví dụ giả lập:** 1.000 input token và 500 output token, mức giá chuẩn ở bảng mục 3:

| Model | Input cost | Output cost | Tổng USD |
|---|---:|---:|---:|
| Gemini 2.5 Flash-Lite | $0.000100 | $0.000200 | **$0.000300** |
| OpenAI GPT-6 Luna (Standard) | $0.000100 | $0.000250 | **$0.000350** |
| Anthropic Claude Haiku 4.5 | $0.001000 | $0.002500 | **$0.003500** |

Các số trên là **ví dụ tính toán**, không phải chi phí đã phát sinh của nhóm. Nếu response có cached tokens, tool calls, reasoning tokens, image output, batch/priority hoặc phụ phí, phải áp dụng biểu giá tương ứng **và tránh tính hai lần các token đã nằm trong trường tổng**.

### 7.2. Dữ liệu nên ghi vào nhật ký

Mỗi lần gọi API, giữ tối thiểu:

```text
usageId, runId, channel, step, task,
provider, modelId, pricingVersion, startedAt, finishedAt,
inputTokens, outputTokens, cachedInputTokens (nếu có),
costUsd, currency, costSource [PROVIDER|ESTIMATED],
status [SUCCESS|FAILED|CANCELLED|PARTIAL],
requestId (nếu API hỗ trợ), errorCode (đã lọc bí mật)
```

- Sử dụng `TokenUsage(inputTokens, outputTokens, costUsd)` hiện có để phát `StepFinished`; nhật ký nội bộ trong Room có thể chi tiết hơn mà **không cần đổi interface**.
- Chỉ cộng usage **cuối cùng** nếu provider gửi usage tổng ở cuối stream; không cộng từng delta hay cộng lặp số liệu cumulative.
- Có thể ước lượng khi request bị ngắt và không nhận usage chính thức, nhưng phải đánh dấu `ESTIMATED` và **không báo là tiền bị trừ thực tế**.
- **Chi phí sinh ảnh:** lưu riêng `imageModel`, `resolution`, `count`, `pricePerImage` hoặc token ảnh tương ứng, rồi cộng vào tổng phiên; template local đặt `apiCost=0`, không đánh đồng với chi phí phần cứng.
- Các khoản phí trả cho tools/grounding hoặc phụ phí (nếu phát sinh) là dòng hạch toán riêng.
- Với tiền, DB nên lưu số nguyên micros USD (`costMicros`) hoặc `BigDecimal` trong business calculation, hạn chế lỗi số thực tích lũy; hợp đồng `TokenUsage.costUsd: Double` vẫn giữ nguyên khi xuất sự kiện.

**Thống kê:** `SUM(cost)` theo ngày/tháng theo múi giờ được người dùng chọn (mặc định local), nhóm theo provider/model/task; mỗi hàng nhật ký có id duy nhất để tránh đếm trùng do retry. Cung cấp thống kê input/output/image riêng và tổng; cho phép xóa nhật ký của người dùng. Key/secret không được ghi vào nhật ký.

## 8. Sinh ảnh & phương án fallback template

**Ưu tiên:** khi người dùng có model sinh ảnh khả dụng và đồng ý chi phí, `IMAGE_PROMPT` viết mô tả ảnh riêng theo kênh; `IMAGE` gọi API ảnh tương ứng, chuẩn hóa ảnh về kích thước cấu hình (ví dụ bản dọc, vuông hoặc ngang) rồi lưu URI cục bộ để preview offline.

**Khi không có API ảnh hoặc hết quota:** thực hiện *local template renderer*, không buộc người dùng nhập key ảnh:

1. Tạo prompt/ý chính ngắn từ nội dung đã viết (chỉ dùng text đã sẵn có; không yêu cầu gọi LLM khi offline).
2. Chọn template theo kênh: tiêu đề, nền màu/gradient, hình đơn giản hoặc asset có quyền sử dụng, logo/nhãn tùy chọn.
3. Android `Canvas`/`Bitmap` và `Paint`/typeface dựng ảnh trực tiếp; hỗ trợ tiếng Việt có dấu, bố cục tự xuống dòng, tỷ lệ và vùng an toàn phù hợp kênh.
4. Export ảnh PNG/JPEG vào app-specific storage, trả `content://` URI qua `FileProvider` khi cần chia sẻ, lưu đường dẫn/metadata vào Room.
5. UI cho người dùng xem/sửa/thử lại ảnh. **Không tự đăng**. Kiểm tra fallback hoạt động khi offline, khi key rỗng, bị 401, 429 hoặc API image không hỗ trợ.

**Giới hạn thực tế:** Ảnh template **không phải** ảnh AI sinh từ mô hình, nên UI và báo cáo cần phân biệt `GENERATED_AI` và `TEMPLATE`. Template local có thể đáp ứng yêu cầu có ảnh khi không có API, nhưng không cam kết độ sáng tạo của hình do AI tạo ra.

## 9. Những điểm cần cân nhắc thay đổi `core/ai` (chỉ đề xuất)

Qua đối chiếu code hiện tại, **chưa phát hiện breaking change nào bắt buộc cho riêng tài liệu nghiên cứu này**. Các hợp đồng hiện có đã bao phủ các thao tác nền của F2/F8. Dưới đây là các khả năng cải tiến ở giai đoạn implementation; **không sửa code trong PR Issue #3**:

| Hạng mục | Hiện trạng | Đề xuất | Có cần Issue `contract`? |
|---|---|---|---|
| Ghi usage theo bước | `StepFinished(channel, step, TokenUsage)` | Đủ để báo UI; log chi tiết trong `data/ai`/Room | Chưa cần |
| Usage ảnh | `TokenUsage` chỉ có input/output/cost | Trong data log thêm ảnh, model, kích thước, nguồn giá | Chưa cần nếu log nội bộ |
| Chọn model/provider từng task | `LlmTask`, `LlmProvider` có sẵn; `ChatRequest` chưa mang model | Dùng cấu hình BYOK + resolver trong implementation; không ép bên gọi tự truyền model | Chưa cần |
| Lỗi có mã máy đọc | `ChannelFailed.reason` là String | Có thể dùng error enum trong implementation/UI mapping; tránh phá chữ ký hiện có | Chỉ mở `contract` nếu cần thay API công khai |
| Run ID / provenance event | `AgentEvent` chưa có `runId` | Lưu `runId` ở Room và repository, chặn event từ run cũ ở ViewModel | Chưa cần; nếu thêm field public phải xin duyệt |
| Regenerate image | `regenerateImage(draft): String` | Dùng URI kết quả + metadata ở data layer; nếu cần trả thêm kiểu kết quả thì mở issue | **Có**, nếu đổi return type/chữ ký |
| BYOK key validation | `LlmClient` chưa có `validateKey` | Tạo service nội bộ `KeyValidator` / `ProviderAdapter`; không thêm vào `LlmClient` khi chưa chốt | Chỉ mở `contract` nếu định mở public API |

Nếu thật sự cần thay đổi interface/core: mở Issue nhãn `contract`, mô tả API cũ/mới, tác động F2/F4/F6/F8, test migration; xin ý kiến cả bốn thành viên theo `AGENTS.md` trước khi triển khai.

## 10. Kế hoạch triển khai và test sau nghiên cứu

**Đề xuất công việc kỹ thuật ở M2 (ngoài phạm vi Issue #3):**

1. **BYOK:** màn hình nhập và validate key; encrypted key storage với Android Keystore; provider/model resolver, lựa chọn theo `LlmTask`.
2. **Text adapters:** `GeminiClient` và `OpenAiClient`; map SSE → `ChatChunk`, usage, lỗi; giả lập network cho unit test.
3. **Orchestrator:** `ContentAgentImpl` tạo brief, supervisor + kênh song song, 6 bước, cancel/retry per channel.
4. **Image:** adapter API ảnh có lựa chọn, template fallback offline, preview + giữ bản nháp chờ duyệt.
5. **Room log:** usage theo provider/model/step, dashboard theo ngày/tháng, cảnh báo chi phí dự kiến.

**Các trường hợp cần test:**

| ID | Tình huống | Kỳ vọng |
|---|---|---|
| T01 | API key sai | Thông báo `INVALID_AUTH`, không lưu key dưới dạng plaintext |
| T02 | Key đúng nhưng model không khả dụng | Báo lỗi quyền/model, không ghi nhầm là key sai |
| T03 | SSE có nhiều delta | Text xuất hiện tăng dần, không nhân đôi token usage |
| T04 | Facebook sub-agent lỗi | LinkedIn vẫn `ChannelDone` |
| T05 | Hủy giữa chừng | Các request con kết thúc, không phát completion giả |
| T06 | Regenerate LinkedIn | Không tạo lại Facebook |
| T07 | Sinh ảnh không có key/hết quota | Xuất ảnh template, phân loại nguồn đúng |
| T08 | Mất mạng khi mở app | Xem được draft/log Room đã lưu, API mới báo offline |
| T09 | Tạo draft xong | Không tự gọi API đăng bài; chờ F4 duyệt |
| T10 | Usage request cuối stream | Cộng đúng số token và USD theo model tại thời điểm gọi |
| T11 | Retry khi 429 | Backoff giới hạn, không tạo log/chi phí trùng |
| T12 | API có thông báo chứa bí mật | Không log key/token hay đưa nguyên thông báo nhạy cảm vào UI |

Thực hiện unit test bằng **JUnit + `kotlinx-coroutines-test` + Fake**, dùng `runTest`, `TestDispatcher`, `Turbine` nếu dependency đã được nhóm duyệt; chỉ thêm dependency qua `gradle/libs.versions.toml` theo `AGENTS.md`. Đây là **test plan**, chưa phải kết quả test thật.

## 11. Tóm tắt cho slide research (đề xuất 5 slide)

**Slide 1 – Bài toán và mục tiêu:** Social Agent viết bài cho Facebook Page & LinkedIn, hiển thị streaming, ảnh, chạy đa kênh, BYOK. F2/F8 là bắt buộc; không tự đăng khi chưa duyệt.

**Slide 2 – So sánh provider:** Gemini Flash-Lite $0.10/$0.40 / 1M token và có free tier giới hạn; OpenAI Luna $0.10/$0.50; Anthropic Haiku 4.5 $1/$5; OpenRouter giá phụ thuộc model, có model `:free`. Cần phân biệt sinh text và sinh ảnh.

**Slide 3 – Quyết định:** Chọn **Gemini + OpenAI** giai đoạn đầu vì hỗ trợ ≥2 provider, có streaming và hệ sinh thái sinh ảnh. Anthropic/OpenRouter để mở rộng. Kết quả là đề xuất chưa benchmark.

**Slide 4 – Kiến trúc Agent:** `ContentAgent.generate()` → `channelFlow + supervisorScope` → một coroutine/sub-agent/kênh → 6 bước → `Flow<AgentEvent>` → ViewModel/Room/UI. Hủy được, lỗi độc lập, chạy lại một kênh.

**Slide 5 – BYOK, chi phí, fallback:** validate key qua `/models` hoặc `/key`, mã hóa bằng Keystore + ciphertext, không gửi server nhóm; ghi token/cost per step và theo tháng; ảnh template offline khi không có API ảnh; người dùng duyệt trước khi đăng.

## 12. Kết luận và phạm vi hoàn thành Issue #3

Tài liệu đã bao phủ các hạng mục **research** của Issue #3: ≥3 provider, hai lựa chọn đề xuất, key validation, streaming/SSE, orchestrator chạy song song & cancellation, tính token/chi phí, fallback template, đánh giá thay đổi `core/` và tóm tắt slide. **Chưa thực hiện:** đo đạc API với key thực, benchmark, triển khai Kotlin hoặc unit test; các việc đó thuộc những Issue phát triển sau. PR của Issue #3 vẫn cần **2 người khác review/approve** trước merge theo quy định nhóm.

## 13. Tài liệu tham khảo chính thức (tra cứu 02/11/2026)

### Mã nguồn/đề bài nhóm
1. [Issue #3](https://github.com/Bthangnguyen/Mobile-Project-/issues/3)
2. [Kiến trúc](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/docs/architecture.md)
3. [ContentAgent](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/ai/ContentAgent.kt)
4. [LlmClient](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/ai/LlmClient.kt)
5. [Agent models](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/model/Agent.kt) / [LLM models](https://github.com/Bthangnguyen/Mobile-Project-/blob/chore/project-setup/app/src/main/java/com/lttbdd/socialagent/core/model/Llm.kt)

### API, giá, streaming và bảo mật
6. [OpenAI API Pricing](https://developers.openai.com/api/docs/pricing)
7. [OpenAI API streaming](https://developers.openai.com/api/docs/guides/streaming-responses)
8. [OpenAI List Models](https://developers.openai.com/api/reference/resources/models/methods/list)
9. [Gemini Pricing](https://ai.google.dev/gemini-api/docs/pricing)
10. [Gemini Models API](https://ai.google.dev/api/models) và [API Keys](https://ai.google.dev/gemini-api/docs/api-key)
11. [Anthropic Pricing](https://platform.claude.com/docs/en/about-claude/pricing), [SSE Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming), [Models API](https://platform.claude.com/docs/en/api/models/list)
12. [OpenRouter Free Models](https://openrouter.ai/collections/free-models), [Authentication](https://openrouter.ai/docs/api/reference/authentication), [Rate Limits](https://openrouter.ai/docs/api/reference/limits)
13. [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

> **Trạng thái nghiên cứu:** Dựa trên tài liệu nhà cung cấp và mã nguồn công khai của nhóm; các quyết định tích hợp, chi phí thực tế, khả năng model khả dụng theo khu vực và thời điểm phải được thử lại trong giai đoạn implementation.
