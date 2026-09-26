package com.hanyou.brain.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

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
    public ResponseEntity<StreamingResponseBody> qa(@RequestBody(required = false) Map<String, String> body) {
        return sse(questionOf(body), aiClient::streamQa);
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
     */
    @PostMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> agent(@RequestBody(required = false) Map<String, String> body) {
        return sse(questionOf(body), aiClient::streamAgent);
    }

    /** 取问题。body 允许缺省（前端探活时会发空体），此时按空串走 */
    private static String questionOf(Map<String, String> body) {
        return body == null ? "" : body.getOrDefault("question", "");
    }

    /**
     * 两个流式端点共用的外壳：建 SSE 响应、检查 AI 是否启用、把上游每一行写回。
     *
     * <p>抽出来的原因不是"少写几行"，而是**两个端点的帧格式必须完全一致**。
     * 如果各写一份，改了一处（比如忘了 {@code X-Accel-Buffering}）就会有一个端点
     * 在反代后面变成"最后一次性出现"，而这种差异极难发现。
     *
     * @param question 用户问题
     * @param upstream 上游调用，签名与 {@link AiClient#streamQa} 一致
     */
    private ResponseEntity<StreamingResponseBody> sse(
            String question, BiConsumer<String, Consumer<String>> upstream) {
        StreamingResponseBody stream = output -> {
            if (!aiClient.isEnabled()) {
                writeEvent(output, "data: {\"type\":\"error\",\"message\":\"AI 能力未启用\"}\n\n");
                return;
            }
            upstream.accept(question, line -> writeEvent(output, line + "\n"));
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
