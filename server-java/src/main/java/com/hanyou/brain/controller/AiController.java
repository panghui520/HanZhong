package com.hanyou.brain.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
 * M3 文旅知识问答的对外接口。
 *
 * <p>前端只认这一层，不直连 Python。两个接口的分工：
 * <ul>
 *   <li>{@code GET /api/ai/health} —— 给页面用来显示"知识库就绪/未就绪"，
 *       以及服务不可用时给出可操作的提示；</li>
 *   <li>{@code POST /api/ai/qa} —— SSE 流式问答，逐字返回。</li>
 * </ul>
 *
 * <p>降级原则：AI 服务没起来时，{@code /api/ai/qa} **不返回 5xx**，而是照常建立
 * SSE 连接、发一条 error 事件再结束。这样前端只有一套解析逻辑，
 * 不需要为"连不上"单独写一个分支，也不会出现白屏。
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
     * 流式问答。响应体是 SSE，事件协议见 server-ai/app/qa.py：
     * meta → delta（多条）→ done，任一步出错则发 error。
     */
    @PostMapping(value = "/qa", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> qa(@RequestBody(required = false) Map<String, String> body) {
        String question = body == null ? "" : body.getOrDefault("question", "");

        StreamingResponseBody stream = output -> {
            if (!aiClient.isEnabled()) {
                writeEvent(output, "data: {\"type\":\"error\",\"message\":\"AI 能力未启用\"}\n\n");
                return;
            }
            aiClient.streamQa(question, line -> writeEvent(output, line + "\n"));
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
