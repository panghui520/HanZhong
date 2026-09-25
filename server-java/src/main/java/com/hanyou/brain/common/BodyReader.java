package com.hanyou.brain.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 请求体取值的公共工具（M6 起）。
 *
 * <p><b>为什么统一用 {@code Map<String, Object>} 接请求体</b>，
 * 而不是 {@code Map<String, String>} 或专用 DTO：
 * 声明成 {@code Map<String, String>} 时，客户端只要多传一个非标量字段
 * （把整个对象回传、或传一个数组），Jackson 就抛
 * {@code HttpMessageNotReadableException}，落到兜底分支报 9000
 * 「服务内部错误」—— 而问题其实在客户端，这个报错会把排查方向带偏。
 * 实测踩到过，见 M6 验收记录。
 *
 * <p>用 Object 接住、只取自己认识的键，多传什么都不影响；
 * 少传或不合法则返回 null，由服务层给出具体的错误码与文案。
 *
 * <p>下面这些方法**只接受标量**：数组与对象一律当"没传"。
 * 唯一的例外是 {@link #strList}，那是给评价图片这种确实要数组的字段用的。
 */
public final class BodyReader {

    private BodyReader() {
    }

    /** 取字符串。数字/布尔也接（表单值可能被 JSON 化成非字符串），数组与对象一律当没传 */
    public static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v instanceof Map || v instanceof Iterable || v.getClass().isArray()) {
            return null;
        }
        return String.valueOf(v);
    }

    /** 取整数。JSON 里可能是数字也可能是纯数字字符串，两种都接，其余返回 null */
    public static Integer intOf(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && s.matches("\\d+")) {
            return Integer.valueOf(s);
        }
        return null;
    }

    /**
     * 取布尔。只认真正的布尔，或 "true" / "false" 字符串。
     *
     * <p>不认识的取值返回 null，而**不是猜一个默认值**：调用方是
     * "同意退款 / 拒绝退款"这类直接涉及钱的判断，猜错的后果是
     * "本来要拒绝的退款被同意了"。宁可报参数错，也不要替用户做决定。
     */
    public static Boolean boolOf(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** 取字符串数组。不是数组就返回 null，由服务层按"没有"处理 */
    public static List<String> strList(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (!(v instanceof Iterable<?> it)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : it) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
