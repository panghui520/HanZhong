package com.hanyou.brain.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.VoUtils;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.Product;
import com.hanyou.brain.entity.ProductCategory;
import com.hanyou.brain.mapper.ProductCategoryMapper;
import com.hanyou.brain.mapper.ProductMapper;
import com.hanyou.brain.service.ProductService;
import com.hanyou.brain.service.support.NameResolver;
import com.hanyou.brain.vo.ProductCategoryVO;
import com.hanyou.brain.vo.ProductVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final NameResolver nameResolver;
    private final HanYouProperties props;

    @Override
    public List<ProductVO> listProducts(String poiId, String experienceId, String categoryCode, String keyword) {
        LambdaQueryWrapper<Product> q = new LambdaQueryWrapper<Product>()
                .eq(Product::getCityCode, props.getCity())
                .eq(Product::getStatus, 1)
                .eq(StringUtils.hasText(poiId), Product::getPoiId, poiId)
                .eq(StringUtils.hasText(experienceId), Product::getExperienceId, experienceId)
                .eq(StringUtils.hasText(categoryCode), Product::getCategoryCode, categoryCode)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Product::getName, keyword)
                        .or().like(Product::getStory, keyword)
                        .or().like(Product::getTags, keyword)
                        .or().like(Product::getOriginVillage, keyword))
                // 按产品编码排序：编码升序意味着数据包的编号顺序就是展示顺序，
                // 想调整首页推荐哪几款，改数据包里的编号即可，不用动代码。
                //
                // 刻意不按 category_code 排：分类编码的字典序是 CAT-CRAFT < CAT-CURED
                // < CAT-FRESH < CAT-GRAIN ... < CAT-TEA，首页按"每个分类取一款"取前四条
                // 时会先取到文创与腊味，把本地的招牌产品（茶叶）挤出首页。
                // 分类聚合是筛选视图的事，交给 category_code 查询参数，不该由默认排序兼职。
                .orderByAsc(Product::getId);

        return toVOs(productMapper.selectList(q));
    }

    @Override
    public ProductVO getProduct(String id) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "产品不存在：" + id);
        }
        return toVOs(List.of(p)).get(0);
    }

    @Override
    public List<ProductCategoryVO> listCategories() {
        List<ProductCategory> categories = productCategoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getCityCode, props.getCity())
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getCode));

        // 计数只取 category_code 一列，不把整行产品拖回来。
        //
        // 用 Java 聚合而不是 SQL group by，是为了让这里的计数和 /api/products
        // 的列表出自同一套过滤条件（同城 + 在售）。换成 group by 就有两份
        // where 子句要同步维护，哪天列表加了"仅上架新品"，计数会先对不上。
        List<Product> rows = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .select(Product::getCategoryCode)
                .eq(Product::getCityCode, props.getCity())
                .eq(Product::getStatus, 1));
        Map<String, Long> countByCode = rows.stream()
                .filter(p -> StringUtils.hasText(p.getCategoryCode()))
                .collect(Collectors.groupingBy(Product::getCategoryCode, Collectors.counting()));

        // 一个分类都没有、或者产品数全是 0 时也照样返回全部分类：
        // 首页的筛选条不该因为"这个城市暂时没上架产品"就整条消失
        List<ProductCategoryVO> out = new ArrayList<>(categories.size());
        for (ProductCategory c : categories) {
            ProductCategoryVO v = new ProductCategoryVO();
            v.setCode(c.getCode());
            v.setName(c.getName());
            v.setSort(c.getSort());
            v.setProductCount(countByCode.getOrDefault(c.getCode(), 0L).intValue());
            out.add(v);
        }
        return out;
    }

    /**
     * 批量转换。
     *
     * <p>三张表的名称一次性查出来再分发：乡村名、体验名、分类名。
     * 一条产品要显示"来自「高山茶园采制一日」"，这个"来自哪里"是本项目
     * 区别于普通电商的核心信息，不能留给前端自己拼。
     */
    private List<ProductVO> toVOs(List<Product> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<String, String> poiNames = nameResolver.poiNames(
                list.stream().map(Product::getPoiId).collect(Collectors.toSet()));
        Map<String, String> experienceNames = nameResolver.experienceNames(
                list.stream().map(Product::getExperienceId).collect(Collectors.toSet()));
        Map<String, String> categoryNames = nameResolver.categoryNames();

        return list.stream().map(p -> {
            ProductVO v = new ProductVO();
            v.setId(p.getId());
            v.setPoiId(p.getPoiId());
            v.setPoiName(poiNames.get(p.getPoiId()));
            v.setExperienceId(p.getExperienceId());
            v.setExperienceName(experienceNames.get(p.getExperienceId()));
            v.setCategoryCode(p.getCategoryCode());
            v.setCategory(categoryNames.get(p.getCategoryCode()));
            v.setName(p.getName());
            v.setSpec(p.getSpec());
            v.setPrice(VoUtils.toDouble(p.getPrice()));
            v.setOriginVillage(p.getOriginVillage());
            v.setStock(p.getStock());
            v.setTags(VoUtils.splitTags(p.getTags()));
            v.setStory(p.getStory());
            v.setScene(p.getScene());
            v.setDataOrigin(p.getDataOrigin());
            v.setSourceUrl(p.getSourceUrl());
            return v;
        }).collect(Collectors.toCollection(ArrayList::new));
    }
}
