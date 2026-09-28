package com.hanyou.brain.service.support;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.Experience;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.ProductCategory;
import com.hanyou.brain.mapper.ExperienceMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.ProductCategoryMapper;

import lombok.RequiredArgsConstructor;

/**
 * 名称补全器：把 VO 里的 id 换成可以直接显示的名字。
 *
 * <p>为什么要单独一个组件：产品要显示所属乡村与体验名、体验要显示所属乡村名，
 * M4 行程规划还要按名字拼"第三天去XX村采茶"。如果每个 Service 各写一遍，
 * 就会出现"某个接口查了某个没查"的不一致；集中在这里，规则只有一份。
 *
 * <p>全部是批量查询（一次 IN 查回一批名字），没有在循环里 selectById——
 * 列表页几十条数据、每条都查一次，就是几十次往返。
 */
@Component
@RequiredArgsConstructor
public class NameResolver {

    private final PoiMapper poiMapper;
    private final ExperienceMapper experienceMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final HanYouProperties props;

    /** poi.id -> 资源名 */
    public Map<String, String> poiNames(Collection<String> ids) {
        Set<String> keys = clean(ids);
        if (keys.isEmpty()) {
            // ★ 刻意不用 Map.of()。它返回的不可变 Map 在 get(null) 时抛
            // NullPointerException，而调用方拿的 id **真的可能是 null**：
            // 农产品只要挂体验锚点就不必挂产地（见 V2 的 chk_product_traceable），
            // 那种行 poi_id 就是 null。一旦某个查询返回的产品恰好全都没挂产地，
            // keys 为空 -> 这里返回 Map.of() -> 调用方 get(null) -> 整个接口报 9000
            // 「服务内部错误」，而真正的原因是"这批产品都没产地"，极难定位。
            // Collections.emptyMap() 的 get 对任何 key（含 null）都返回 null。
            //
            // 实测踩过：M10 新增一条只挂体验的农产品，接口直接 9000。
            return Collections.emptyMap();
        }
        // 只取 id 与 name 两列：列表页补名字不需要把 summary、坐标一起拖回来
        List<Poi> rows = poiMapper.selectList(new LambdaQueryWrapper<Poi>()
                .select(Poi::getId, Poi::getName)
                .in(Poi::getId, keys));
        return rows.stream().collect(Collectors.toMap(Poi::getId, Poi::getName, (a, b) -> a));
    }

    /** experience.id -> 体验名 */
    public Map<String, String> experienceNames(Collection<String> ids) {
        Set<String> keys = clean(ids);
        if (keys.isEmpty()) {
            // ★ 刻意不用 Map.of()。它返回的不可变 Map 在 get(null) 时抛
            // NullPointerException，而调用方拿的 id **真的可能是 null**：
            // 农产品只要挂体验锚点就不必挂产地（见 V2 的 chk_product_traceable），
            // 那种行 poi_id 就是 null。一旦某个查询返回的产品恰好全都没挂产地，
            // keys 为空 -> 这里返回 Map.of() -> 调用方 get(null) -> 整个接口报 9000
            // 「服务内部错误」，而真正的原因是"这批产品都没产地"，极难定位。
            // Collections.emptyMap() 的 get 对任何 key（含 null）都返回 null。
            //
            // 实测踩过：M10 新增一条只挂体验的农产品，接口直接 9000。
            return Collections.emptyMap();
        }
        List<Experience> rows = experienceMapper.selectList(new LambdaQueryWrapper<Experience>()
                .select(Experience::getId, Experience::getName)
                .in(Experience::getId, keys));
        return rows.stream().collect(Collectors.toMap(Experience::getId, Experience::getName, (a, b) -> a));
    }

    /**
     * category.code -> 分类名。
     *
     * <p>分类只有十来行，不做 id 过滤、整表查回来：让调用方先收集"用到的编码"
     * 再传进来，代码更长而收益为零。产品量级涨到需要分页时再改。
     */
    public Map<String, String> categoryNames() {
        List<ProductCategory> rows = productCategoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>()
                        .select(ProductCategory::getCode, ProductCategory::getName)
                        .eq(ProductCategory::getCityCode, props.getCity()));
        return rows.stream().collect(Collectors.toMap(ProductCategory::getCode, ProductCategory::getName, (a, b) -> a));
    }

    /** 去掉 null 与空串。列表里混进 null 会让 in 条件拼出 "id IN (?, NULL)"，白查一趟 */
    private static Set<String> clean(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (StringUtils.hasText(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
