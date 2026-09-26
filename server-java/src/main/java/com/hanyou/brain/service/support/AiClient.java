package com.hanyou.brain.service.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyou.brain.config.HanYouProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 访问 Python AI 服务的客户端。
 *
 * <p>为什么不让前端直接调 Python：Python 只监听 127.0.0.1，且要求
 * X-Internal-Token。如果把它暴露给浏览器，令牌就得发给前端，等于把内部接口公开；
 * 而且限流、审计、统一错误码都得在两边各做一遍。放在 Java 层代理一次，
 * 前端只需要认一个后端地址，密钥不出服务端。
 *
 * <p>用 JDK 自带的 java.net.http.HttpClient，不引入 WebClient / Feign：
 * 这里只有两个接口，多一个依赖不划算，而 JDK 的客户端本身就支持流式读取响应体。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClient {

    private final HanYouProperties properties;
    private final ObjectMapper objectMapper;

    private HttpClient client;

    @PostConstruct
    void init() {
        HanYouProperties.Ai ai = properties.getAi();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ai.getConnectTimeoutSeconds()))
                // 必须显式锁 HTTP/1.1。JDK 客户端默认对明文连接发起 h2c 升级握手，
                // 而 uvicorn 只讲 HTTP/1.1：GET 没请求体时看不出来，
                // POST 带 body 会变成服务端读到一半连接就断了（ClientDisconnect）。
                .version(HttpClient.Version.HTTP_1_1)
                // 不跟随重定向：AI 服务是本机固定端点，跟随重定向只会掩盖配置错误
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean isEnabled() {
        return properties.getAi().isEnabled();
    }

    /**
     * 取 AI 服务健康状态。
     *
     * <p>返回原始 JSON 字符串而不是解析成对象：health 的字段会随模块增加而变
     * （M3 加了 lexical，M4 还会加多智能体的状态），Java 侧不该跟着改一遍。
     * 这里的职责是转发，不是理解。
     *
     * @return 健康信息 JSON；服务不可用时返回 null，不抛异常
     */
    public String health() {
        return getJson("/ai/health");
    }

    /**
     * 取推荐问题。
     *
     * <p>知识库不可用时返回空列表而不是 null——推荐区是页面装饰，
     * 缺了它页面仍然可用，不该让前端为它单独处理一种失败。
     */
    public List<String> suggestions() {
        String raw = getJson("/ai/suggestions");
        if (raw == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (IOException e) {
            log.warn("[AI] 推荐问题解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 通用的 GET，取回原始 JSON。失败一律返回 null，由调用方决定怎么降级 */
    private String getJson(String path) {
        HttpRequest request = baseRequest(path).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("[AI] {} 返回 HTTP {}：{}", path, response.statusCode(), response.body());
                return null;
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[AI] {} 不可达：{}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 发起一次问答，把 SSE 行逐行交给消费者。
     *
     * <p>逐行转发而不是先收完再返回：这正是流式生成的意义——用户在看到
     * "正在生成"的同时就拿到第一批字，而不是等 10 秒后一次性出现整段答案。
     *
     * <p>连接失败时**不抛异常**，而是回调一行 error 事件再正常结束。
     * 原因是调用方在写一个已经开始的 SSE 响应流，此时改 HTTP 状态码已经来不及，
     * 只能把故障表达成协议内的事件。前端因此只有一条错误处理路径。
     *
     * @param question  用户问题
     * @param onLine    每读到一行 SSE 就回调一次（不含换行符）
     */
    public void streamQa(String question, Consumer<String> onLine) {
        streamQuestion("/ai/qa", question, null, onLine);
    }

    /**
     * 发起一次 Agent 对话（M4），把 SSE 行逐行交给消费者。
     *
     * <p>与 {@link #streamQa} 走**同一条隧道**：同一个内部令牌、同一套超时、
     * 同样逐行转发。唯一的差别是路径与事件协议（多了 tool / cards）。
     * 所以这里不复用一套新的 HTTP 逻辑，而是共用 {@code streamQuestion}——
     * 两套并行的转发代码迟早在超时或错误处理上分叉。
     *
     * <p>为什么不把 Agent 塞进 {@code /ai/qa}：见 {@code AiController} 的类注释。
     *
     * @param question 用户问题
     * @param context  本次行程的上下文（M4 阶段二）。空 Map 表示"没有上下文"，
     *                 此时请求体与阶段一完全一致 —— 未登录用户走的就是这条路。
     *                 上下文由服务端从库里读出来，**不接受前端传入**：
     *                 否则任何人都能伪造"我住在某某酒店"去影响检索结果。
     * @param onLine   每读到一行 SSE 就回调一次（不含换行符）
     */
    public void streamAgent(String question, Map<String, Object> context, Consumer<String> onLine) {
        streamQuestion("/ai/agent", question, context, onLine);
    }

    /** 两个流式端点的共同实现：POST 一个问题（可选带上下文），逐行回吐 SSE */
    private void streamQuestion(String path, String question, Map<String, Object> context,
                                Consumer<String> onLine) {
        HanYouProperties.Ai ai = properties.getAi();
        String payload = buildPayload(question, context);

        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(ai.getReadTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                onLine.accept(errorEvent("AI 服务返回 HTTP " + response.statusCode()));
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            }
        } catch (ConnectException e) {
            log.warn("[AI] {} 连接失败：{}", path, e.getMessage());
            onLine.accept(errorEvent("AI 服务未启动或端口不通，请先运行 server-ai（" + ai.getBaseUrl() + "）"));
        } catch (IOException e) {
            log.warn("[AI] {} 读取流失败：{}", path, e.getMessage());
            onLine.accept(errorEvent("与 AI 服务的连接中断：" + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onLine.accept(errorEvent("请求被中断"));
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        HanYouProperties.Ai ai = properties.getAi();
        return HttpRequest.newBuilder()
                .uri(URI.create(ai.getBaseUrl() + path))
                .header("X-Internal-Token", ai.getInternalToken())
                .header("Accept", "text/event-stream");
    }

    /**
     * 组装请求体。没有上下文时退化成与阶段一完全相同的 {@code {"question": "..."}}。
     *
     * <p>有上下文时改用 Jackson 序列化，而不是继续手拼字符串：上下文是嵌套结构
     * （{@code selected_hotel.location} 是"经度,纬度"，里面有逗号），
     * 手拼要自己保证每一层都转义正确，而这里一旦拼坏，Python 侧收到的是
     * 400 或者更糟的"字段静默消失"。{@link #escapeJson} 保留给降级分支与
     * SSE 事件用——那些地方只有一个字符串要转义，不值得引入序列化。
     *
     * <p>序列化失败时**退化为不带上下文**继续提问，而不是让整次对话失败：
     * 上下文是增强项，缺了它助手只是变回阶段一的样子，仍然能回答。
     */
    private String buildPayload(String question, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "{\"question\":\"" + escapeJson(question) + "\"}";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("context", context);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("[AI] 上下文序列化失败，本次退化为不带上下文提问：{}", e.getMessage());
            return "{\"question\":\"" + escapeJson(question) + "\"}";
        }
    }

    /** 组装一行 SSE error 事件，格式与 Python 侧完全一致 */
    private static String errorEvent(String message) {
        return "data: {\"type\":\"error\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    /**
     * 最小 JSON 字符串转义。只处理必须转义的字符。
     *
     * <p>没有引入 Jackson 的 ObjectMapper 来做这件事：这里要转义的只有一个
     * 用户输入的字符串，用序列化框架反而多一层"字段名会不会被改写"的不确定性。
     */
    private static String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
