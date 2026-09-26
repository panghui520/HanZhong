package com.hanyou.brain.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyou.brain.auth.AuthUser;
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.TripService;
import com.hanyou.brain.service.support.AiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 能力（M3 知识问答 + M4 旅游助手）的对外接口。
 *
 * <p>前端只认这一层，不直连 Python。四个接口的分工：
 * <ul>
 *   <li>{@code GET /api/ai/health} —— 给页面用来显示"知识库就绪/未就绪"，
 *       以及服务不可用时给出可操作的提示；</li>
 *   <li>{@code GET /api/ai/suggestions} —— 首页推荐问题；</li>
 *   <li>{@code POST /api/ai/qa} —— M3 知识问答，SSE 流式，逐字返回；</li>
 *   <li>{@code POST /api/ai/agent} —— M4 旅游助手，SSE 流式，
 *       比 qa 多出 tool（工具调用状态）与 cards（酒店卡片）两类事件。</li>
 * </ul>
 *
 * <p>降级原则：AI 服务没起来时，两个流式接口都**不返回 5xx**，而是照常建立
 * SSE 连接、发一条 error 事件再结束。这样前端只有一套解析逻辑，
 * 不需要为"连不上"单独写一个分支，也不会出现白屏。
 *
 * <p><b>权限</b>：{@code /api/ai/**} 在 {@code SecurityConfig} 里是整段 permitAll
 * （问答能力对未登录游客开放，与 M3 一致）。所以新增 {@code /agent} 端点
 * **不需要再配一次权限**——这里记一笔，免得后来人以为漏配了。
 * 反过来说，这一层**不能放需要登录才能做的动作**（下单、改资料），
 * 那些必须走各自的受保护接口。
 *
 * <p><b>M4 阶段二起，{@code /agent} 会带上登录用户的行程上下文。</b>
 * 上下文由服务端从 {@code trip} / {@code trip_context} 读出来，
 * 前端传什么都不看（见 {@link AiClient#streamAgent}）。未登录时
 * {@code me} 为 null，请求体与阶段一完全一致 —— 助手照常工作，只是没有记忆。
 * 这个"登录是可选的增强"是刻意的：问答的核心价值不依赖登录，
 * 而"选择酒店"这类写操作走 {@code /api/trips/**}，那条路必须登录。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final TripService tripService;

    /** AI 健康状态。字段随 Python 侧演进，这里只做透传，不定义 DTO */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        if (!aiClient.isEnabled()) {
            return Result.fail(ErrorCode.AI_DISABLED);
        }
        String raw = aiClient.health();
        if (raw == null) {
            return Result.fail(ErrorCode.AI_UNAVAILABLE);
        }
        try {
            return Result.ok(objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            }));
        } catch (IOException e) {
            log.warn("[AI] health 响应解析失败：{}", e.getMessage());
            return Result.fail(ErrorCode.AI_UNAVAILABLE);
        }
    }

    /** 推荐问题。知识库不可用时返回空列表，前端隐藏推荐区即可，不该报错 */
    @GetMapping("/suggestions")
    public Result<List<String>> suggestions() {
        if (!aiClient.isEnabled()) {
            return Result.ok(List.of());
        }
        return Result.ok(aiClient.suggestions());
    }

    /**
     * 流式知识问答。响应体是 SSE，事件协议见 server-ai/app/qa.py：
     * meta → delta（多条）→ done，任一步出错则发 error。
     */
    @PostMapping(value = "/qa", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> qa(@RequestBody(required = false) Map<String, Object> body) {
        String question = questionOf(body);
        return sse(onLine -> aiClient.streamQa(question, onLine));
    }

    /**
     * 流式旅游助手对话（M4）。响应体是 SSE，事件协议见 server-ai/app/agent.py：
     * meta → tool（可多条）→ cards（可选）→ delta（多条）→ done，出错则发 error。
     *
     * <p>为什么不并进 {@code /ai/qa}：qa 的 meta 描述的是"检索行为"
     * （route / mode / sources），agent 的 meta 描述的是"用了哪个工具"
     * （tools / amap），done 里还多一个 tool。两套语义塞进同一个端点，
     * 前端每处都要判断"这次有没有 tool 字段"，而 M3 已验收的契约也会被改动。
     * 多一个端点的代价，远小于污染一个已验收的协议。
     *
     * <p>阶段二起多一步：登录用户会带上他当前行程的上下文（目的地 + 已选酒店 +
     * 坐标 + 预订状态），于是"这附近有什么好吃的"里的"附近"有了确定指代。
     * 未登录时 {@code me} 为 null，上下文为空 —— 与阶段一行为完全一致。
     */
    @PostMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> agent(@RequestBody(required = false) Map<String, Object> body,
                                                      @AuthenticationPrincipal AuthUser me) {
        String question = questionOf(body);
        // 读库放在建 SSE 响应之前：这样"读上下文失败"还能报成正常的 Result 错误，
        // 而不是在一个已经开始写的流里发 error 事件（那时状态码已经发出去了）
        Map<String, Object> context = me == null ? Map.of() : tripService.aiContext(me.userId());
        return sse(onLine -> aiClient.streamAgent(question, context, onLine));
    }

    /**
     * 取问题。body 允许缺省（前端探活时会发空体），此时按空串走。
     *
     * <p>请求体声明成 {@code Map<String, Object>} 而不是 {@code Map<String, String>}：
     * 后者只要客户端多传一个非字符串字段（把整个对象回传、或传一个数组），
     * Jackson 就抛 {@code HttpMessageNotReadableException}，落到兜底分支报
     * 9000「服务内部错误」——而问题其实在客户端。用 Object 接住、只取
     * 自己认识的键，多传什么都不影响。与 M6 起沿用同一套做法。
     */
    private static String questionOf(Map<String, Object> body) {
        if (body == null) {
            return "";
        }
        String question = BodyReader.str(body, "question");
        return question == null ? "" : question;
    }

    /**
     * 两个流式端点共用的外壳：建 SSE 响应、检查 AI 是否启用、把上游每一行写回。
     *
     * <p>抽出来的原因不是"少写几行"，而是**两个端点的帧格式必须完全一致**。
     * 如果各写一份，改了一处（比如忘了 {@code X-Accel-Buffering}）就会有一个端点
     * 在反代后面变成"最后一次性出现"，而这种差异极难发现。
     *
     * <p>参数是"给我一个行接收器，我去把上游接上"的函数，而不是
     * {@code (question, onLine)} 二元组：agent 比 qa 多一个上下文参数，
     * 用二元组就得为它单独开一个三参数重载，两个端点的帧格式立刻有了
     * 两份实现。让调用方闭包捕获自己的参数，这里就只认"行接收器"一件事。
     *
     * @param upstream 接上游的动作，拿到行接收器后自行发起请求
     */
    private ResponseEntity<StreamingResponseBody> sse(Consumer<Consumer<String>> upstream) {
        StreamingResponseBody stream = output -> {
            if (!aiClient.isEnabled()) {
                writeEvent(output, "data: {\"type\":\"error\",\"message\":\"AI 能力未启用\"}\n\n");
                return;
            }
            upstream.accept(line -> writeEvent(output, line + "\n"));
        };

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache")
                // 反向代理若做缓冲会吃掉流式效果，显式关掉
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    /** 写一行并立即冲刷。不 flush 的话字会攒在缓冲区里，流式就变成了"最后一次性出现" */
    private static void writeEvent(java.io.OutputStream output, String text) {
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            // 客户端提前断开（用户切走页面）会走到这里，不是错误，只是没有接收方了
            throw new UncheckedIOException(e);
        }
    }
}
