package com.hanyou.brain.common;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;

/**
 * VO 转换的公共小工具。
 *
 * <p>M1 时 splitTags / toDouble 只是 PoiServiceImpl 的私有方法；M2 起体验与产品
 * 两个 Service 也要用同一套规则，于是提到这里。转换规则必须只有一份——
 * 三个接口对同一种数据库类型的输出口径不一致（一个给 168.0、一个给 "168"），
 * 前端就得为每个接口写一遍兼容代码。
 */
public final class VoUtils {

    private VoUtils() {
    }

    /**
     * 逗号分隔字符串 -> 数组。
     *
     * <p>数据库里存的是 "采茶,制茶,手作" 这种形式（见 db/V2__m2_experience_product.sql），
     * 对外一律给数组。空串与 null 都返回空列表而不是 null，
     * 让前端可以直接 v-for，不必每次判空。
     */
    public static List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** DECIMAL 列在 VO 里统一用 Double：前端只做展示与排序，不需要 BigDecimal 的精度语义 */
    public static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
