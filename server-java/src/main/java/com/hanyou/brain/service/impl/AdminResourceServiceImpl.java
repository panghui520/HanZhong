package com.hanyou.brain.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.VoUtils;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.CartItem;
import com.hanyou.brain.entity.Experience;
import com.hanyou.brain.entity.OrderItem;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiComment;
import com.hanyou.brain.entity.PoiImage;
import com.hanyou.brain.entity.PoiRelation;
import com.hanyou.brain.entity.Product;
import com.hanyou.brain.entity.ProductCategory;
import com.hanyou.brain.entity.TripCheckin;
import com.hanyou.brain.mapper.CartItemMapper;
import com.hanyou.brain.mapper.ExperienceMapper;
import com.hanyou.brain.mapper.OrderItemMapper;
import com.hanyou.brain.mapper.PoiCommentMapper;
import com.hanyou.brain.mapper.PoiImageMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.PoiRelationMapper;
import com.hanyou.brain.mapper.ProductCategoryMapper;
import com.hanyou.brain.mapper.ProductMapper;
import com.hanyou.brain.mapper.TripCheckinMapper;
import com.hanyou.brain.service.AdminResourceService;
import com.hanyou.brain.service.support.NameResolver;
import com.hanyou.brain.vo.AdminPoiVO;
import com.hanyou.brain.vo.AdminProductVO;

import lombok.RequiredArgsConstructor;

/**
 * 资源管理实现（M10）。
 *
 * <p>四条贯穿本类的规则：
 *
 * <p><b>1. 数据包资源要改，先"接管"。</b>新建的资源一律 {@code source=ADMIN}。
 * 编辑一条 {@code PACK} 行时会先把它接管成 ADMIN（见 {@link #updatePoi}），
 * 从此导入器不再覆盖它 —— 这样运营改的东西活得过一次重启。
 * <b>删除仍然只允许 ADMIN 行</b>（见 {@link #deletePoi}）：接管是运营有意识
 * 走出的一步（"我要改它"），删除不是，不该顺手把数据包资源删掉。
 *
 * <p><b>2. 删除前先数引用，有任何一处引用就拒绝。</b>拒绝时把**具体是哪几类**
 * 引用回给运营（"已被历史订单、到访足迹引用"），而不是一句"删除失败"。
 * 运营看到原因才知道下一步该做什么 —— 这个项目里 90% 的排查时间花在
 * "不知道哪里不对"上。
 *
 * <p><b>3. 写操作一律读-改-写，不用"局部更新"。</b>编辑先 selectById 取出整行，
 * 再把请求体里出现的字段覆盖上去，最后 updateById 整行写回。看起来多一次查询，
 * 换来的是"不传的字段不会被 MyBatis-Plus 的 null 忽略策略悄悄留在旧值上"——
 * 那种偏差在界面上表现为"改了这个、那个没变"，极难解释。
 *
 * <p><b>4. 写接口回读数据库再返回。</b>不把内存里的对象直接当结果返回：
 * created_at / updated_at 是数据库填的，不回读就永远是 null。
 */
@Service
@RequiredArgsConstructor
public class AdminResourceServiceImpl implements AdminResourceService {

    private static final Logger log = LoggerFactory.getLogger(AdminResourceServiceImpl.class);

    // ---- 业态编码。取值必须与 db/V1__m1_resource.sql 的 business_type 注释一致 ----
    private static final String BIZ_SCENIC = "SCENIC";
    private static final String BIZ_RURAL = "RURAL_SPOT";
    private static final String BIZ_FOOD = "FOOD";
    private static final String BIZ_LODGING = "LODGING";

    /** 景点入口下的两个业态。乡村旅游与景区同属"景点"，管理端不为它们各开一个入口 */
    private static final Set<String> SCENIC_TYPES = Set.of(BIZ_SCENIC, BIZ_RURAL);

    /** 来源。与 CityPackImporter.SOURCE_PACK 是同一个值，那边是常量、这里是列值 */
    private static final String SOURCE_PACK = "PACK";
    private static final String SOURCE_ADMIN = "ADMIN";

    /** 运营新建资源的编码前缀。与数据包的 P-SCE-001 / PRD-001 区分开，一眼能看出是谁建的 */
    private static final String ID_PREFIX_POI = "P-ADM-";
    private static final String ID_PREFIX_PRODUCT = "PRD-ADM-";

    private final PoiMapper poiMapper;
    private final ProductMapper productMapper;
    private final ExperienceMapper experienceMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final PoiRelationMapper poiRelationMapper;
    private final PoiImageMapper poiImageMapper;
    private final PoiCommentMapper poiCommentMapper;
    private final CartItemMapper cartItemMapper;
    private final OrderItemMapper orderItemMapper;
    private final TripCheckinMapper tripCheckinMapper;
    private final NameResolver nameResolver;
    private final HanYouProperties props;

    // ============================================================
    // 景点 / 美食（poi）
    // ============================================================

    @Override
    public List<AdminPoiVO> listPois(String type, String keyword, Integer status) {
        Set<String> types = businessTypesOf(type);

        LambdaQueryWrapper<Poi> q = new LambdaQueryWrapper<Poi>()
                .eq(Poi::getCityCode, props.getCity())
                .in(Poi::getBusinessType, types)
                .eq(status != null, Poi::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Poi::getName, keyword)
                        .or().like(Poi::getSummary, keyword)
                        .or().like(Poi::getTags, keyword)
                        .or().like(Poi::getDistrict, keyword)
                        // 编码也参与匹配：运营拿着数据包里的编号（P-SCE-007）来找一条资源
                        // 是常见操作，只搜名称会搜不到
                        .or().like(Poi::getId, keyword))
                // ADMIN < PACK 的字典序，所以运营新建的排在前面 —— 那通常是刚在改的那批
                .orderByAsc(Poi::getSource)
                .orderByAsc(Poi::getId);

        List<Poi> rows = poiMapper.selectList(q);
        Map<String, Integer> imageCounts = imageCountsOf(rows.stream().map(Poi::getId).toList());
        return rows.stream().map(p -> toPoiVO(p, imageCounts)).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AdminPoiVO createPoi(String type, Map<String, Object> body) {
        Set<String> types = businessTypesOf(type);
        String businessType = resolveBusinessType(body, types);

        Poi p = new Poi();
        p.setId(nextAdminId(ID_PREFIX_POI, adminPoiIds()));
        p.setCityCode(props.getCity());
        p.setBusinessType(businessType);
        p.setSource(SOURCE_ADMIN);
        // 新建默认上架：运营建一条资源的目的就是让它出现在游客端，
        // 建完还要再点一次"上架"是多此一举。想先藏着不发布，建完点下架即可。
        p.setStatus(1);
        applyPoi(p, body, true);

        poiMapper.insert(p);
        log.info("[M10] 新增资源 type={} id={} name={}", type, p.getId(), p.getName());
        return toPoiVO(poiMapper.selectById(p.getId()), Map.of());
    }

    @Override
    @Transactional
    public AdminPoiVO updatePoi(String type, String id, Map<String, Object> body) {
        Set<String> types = businessTypesOf(type);
        Poi p = requirePoi(id, types);

        // ★ 接管（M10 续）：数据包资源被第一次编辑时，从 PACK 接管为 ADMIN。
        //
        // 为什么要"接管"而不是"直接解锁按钮"：
        //   CityPackImporter 每次启动按 city_code 全量重建业务表。若只是放开编辑
        //   而不改 source，运营改完重启就回到 JSON 原样 —— 界面上显示"保存成功"，
        //   数据却活不过一次重启。那种失败比禁止编辑更糟：禁止编辑至少当场就说清了。
        //
        // 接管做的事只有一件：**把这一行的 source 从 PACK 改成 ADMIN**。
        //   导入器的删除条件（city_code = ? AND source = 'PACK'）从此不再命中它，
        //   它也就不会被重灌覆盖。id 一个字都不动 —— 订单、足迹、体验、资源关系
        //   全是按 P-xxx 引用它的，改 id 等于把这些引用全部打断。
        //
        // 配套改动在 CityPackImporter：插入数据包资源前会跳过已被接管的 id，
        //   否则同一条 P-SCE-001 会被插两次，主键冲突让整个启动失败。
        //
        // 这是**单向**的：接管之后没有"还原为数据包版本"的入口。要还原只能手工
        //   改库（UPDATE poi SET source='PACK' WHERE id=...），改完下次启动
        //   即回到 JSON 原样。当前不做这个入口 —— 运营点了编辑又说"我不要了"
        //   是少数情况，为一个少数情况做一套反向流程，不值得。
        boolean takenOver = !SOURCE_ADMIN.equals(p.getSource());
        if (takenOver) {
            p.setSource(SOURCE_ADMIN);
        }

        // 业态可以改（把一个景区改成乡村旅游、或把美食改成景区），但必须落在
        // 本入口允许的集合里 —— 否则"美食管理"里改一改就能把资源搬到"景点管理"，
        // 而当前页面还停在美食 tab，运营会以为它凭空消失了。
        if (body.containsKey("business_type")) {
            p.setBusinessType(resolveBusinessType(body, types));
        }
        applyPoi(p, body, false);

        poiMapper.updateById(p);
        log.info("[M10] 编辑资源 type={} id={}{}", type, id,
                takenOver ? "（数据包资源已接管为运营资源，重启不再被覆盖）" : "");
        return toPoiVO(poiMapper.selectById(id), imageCountsOf(List.of(id)));
    }

    @Override
    @Transactional
    public AdminPoiVO updatePoiStatus(String type, String id, Integer status) {
        Set<String> types = businessTypesOf(type);
        Poi p = requirePoi(id, types);
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.RESOURCE_STATUS_INVALID);
        }
        // ★ 上下架**刻意不做** requireAdminOwned —— 数据包资源也能下架。
        //
        // 理由：`status` 不在数据包里（citypack/*.json 没有这个字段），它是**运营态**，
        // 所以不受"改了会被重灌覆盖"那条约束。而 PACK 行既不能删也不能改字段，
        // **下架是运营唯一能让它从游客端消失的手段** —— 锁掉它，42 条现有资源
        // 就一条也藏不住，与"下架优先于物理删除"这条产品原则直接冲突。
        // 配套改动：CityPackImporter 现在会把被下架过的行按原状态还原（见该类
        // offlinePoiStatuses 的注释），所以这里的下架能活过重启。

        // 只改一列，不必读-改-写整行
        Poi patch = new Poi();
        patch.setId(id);
        patch.setStatus(status);
        poiMapper.updateById(patch);

        log.info("[M10] 资源{} id={} name={}", status == 1 ? "上架" : "下架", id, p.getName());
        return toPoiVO(poiMapper.selectById(id), imageCountsOf(List.of(id)));
    }

    @Override
    @Transactional
    public void deletePoi(String type, String id) {
        Set<String> types = businessTypesOf(type);
        Poi p = requirePoi(id, types);
        requireAdminOwned(p.getId(), p.getName(), p.getSource());

        List<String> refs = poiReferences(id);
        if (!refs.isEmpty()) {
            throw new BizException(ErrorCode.RESOURCE_IN_USE,
                    "「" + p.getName() + "」已被" + String.join("、", refs) + "引用，不能删除，请改用下架");
        }

        poiMapper.deleteById(id);
        log.info("[M10] 删除资源 type={} id={} name={}", type, id, p.getName());
    }

    // ============================================================
    // 农产品（product）
    // ============================================================

    @Override
    public List<AdminProductVO> listProducts(String keyword, Integer status) {
        LambdaQueryWrapper<Product> q = new LambdaQueryWrapper<Product>()
                .eq(Product::getCityCode, props.getCity())
                .eq(status != null, Product::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Product::getName, keyword)
                        .or().like(Product::getStory, keyword)
                        .or().like(Product::getTags, keyword)
                        .or().like(Product::getOriginVillage, keyword)
                        .or().like(Product::getId, keyword))
                .orderByAsc(Product::getSource)
                .orderByAsc(Product::getId);

        return toProductVOs(productMapper.selectList(q));
    }

    @Override
    @Transactional
    public AdminProductVO createProduct(Map<String, Object> body) {
        Product p = new Product();
        p.setId(nextAdminId(ID_PREFIX_PRODUCT, adminProductIds()));
        p.setCityCode(props.getCity());
        p.setSource(SOURCE_ADMIN);
        p.setStatus(1);
        applyProduct(p, body, true);

        productMapper.insert(p);
        log.info("[M10] 新增农产品 id={} name={}", p.getId(), p.getName());
        return toProductVOs(List.of(productMapper.selectById(p.getId()))).get(0);
    }

    @Override
    @Transactional
    public AdminProductVO updateProduct(String id, Map<String, Object> body) {
        Product p = requireProduct(id);
        requireAdminOwned(p.getId(), p.getName(), p.getSource());

        applyProduct(p, body, false);
        productMapper.updateById(p);

        log.info("[M10] 编辑农产品 id={}", id);
        return toProductVOs(List.of(productMapper.selectById(id))).get(0);
    }

    @Override
    @Transactional
    public AdminProductVO updateProductStatus(String id, Integer status) {
        Product p = requireProduct(id);
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.RESOURCE_STATUS_INVALID);
        }
        // 与 updatePoiStatus 同理：上下架不做 requireAdminOwned，数据包资源也能下架。
        // 配套的"重灌后还原状态"在 CityPackImporter.offlineProductStatuses。

        Product patch = new Product();
        patch.setId(id);
        patch.setStatus(status);
        productMapper.updateById(patch);

        log.info("[M10] 农产品{} id={} name={}", status == 1 ? "上架" : "下架", id, p.getName());
        return toProductVOs(List.of(productMapper.selectById(id))).get(0);
    }

    @Override
    @Transactional
    public void deleteProduct(String id) {
        Product p = requireProduct(id);
        requireAdminOwned(p.getId(), p.getName(), p.getSource());

        List<String> refs = productReferences(id);
        if (!refs.isEmpty()) {
            throw new BizException(ErrorCode.RESOURCE_IN_USE,
                    "「" + p.getName() + "」已被" + String.join("、", refs) + "引用，不能删除，请改用下架");
        }

        productMapper.deleteById(id);
        log.info("[M10] 删除农产品 id={} name={}", id, p.getName());
    }

    // ============================================================
    // 字段应用
    // ============================================================

    /**
     * 把请求体里的 poi 字段写到实体上。
     *
     * @param create true = 正在新建（缺失字段给默认值、必填字段报错）；
     *               false = 正在编辑（**只覆盖请求体里出现的键**，没传的保持原值）
     */
    private void applyPoi(Poi p, Map<String, Object> body, boolean create) {
        if (create || body.containsKey("name")) {
            String name = BodyReader.str(body, "name");
            if (!StringUtils.hasText(name)) {
                throw new BizException(ErrorCode.RESOURCE_NAME_REQUIRED);
            }
            p.setName(name.trim());
        }

        if (create || body.containsKey("district")) p.setDistrict(BodyReader.str(body, "district"));
        if (create || body.containsKey("level")) p.setLevel(BodyReader.str(body, "level"));
        if (create || body.containsKey("lng")) p.setLng(BodyReader.decimal(body, "lng"));
        if (create || body.containsKey("lat")) p.setLat(BodyReader.decimal(body, "lat"));
        if (create || body.containsKey("open_hours")) p.setOpenHours(BodyReader.str(body, "open_hours"));
        if (create || body.containsKey("summary")) p.setSummary(BodyReader.str(body, "summary"));

        // ---- M10 续新增的四列（db/V12）----
        if (create || body.containsKey("address")) p.setAddress(BodyReader.str(body, "address"));
        if (create || body.containsKey("phone")) p.setPhone(BodyReader.str(body, "phone"));
        if (create || body.containsKey("detail")) p.setDetail(BodyReader.str(body, "detail"));
        if (create || body.containsKey("warning_threshold")) {
            java.math.BigDecimal wt = BodyReader.decimal(body, "warning_threshold");
            // 承载率预警线是**比值**，不是人数。允许清空（传空串 → null），
            // 表示"回到 risk_rule 的全局阈值"；但一旦给了值就必须落在 [0.01, 1.00]。
            // 不校验的话，1.5 这种值会让 OVERLOAD 规则永远不触发，而运营以为
            // 自己配了一条更严的线 —— 一个不会响的告警比没有告警更危险。
            if (wt != null
                    && (wt.compareTo(new java.math.BigDecimal("0.01")) < 0
                            || wt.compareTo(java.math.BigDecimal.ONE) > 0)) {
                throw new BizException(ErrorCode.RESOURCE_FIELD_INVALID,
                        "预警阈值是承载率，应在 0.01 到 1.00 之间（如 0.8 表示八成）");
            }
            p.setWarningThreshold(wt);
        }

        if (create || body.containsKey("scene")) p.setScene(BodyReader.str(body, "scene"));
        if (create || body.containsKey("source_url")) p.setSourceUrl(BodyReader.str(body, "source_url"));

        if (create || body.containsKey("ticket_price")) {
            p.setTicketPrice(zeroIfNull(BodyReader.decimal(body, "ticket_price")));
        }
        if (create || body.containsKey("duration_min")) {
            p.setDurationMin(zeroIfNull(BodyReader.intOf(body, "duration_min")));
        }
        if (create || body.containsKey("capacity")) {
            p.setCapacity(zeroIfNull(BodyReader.intOf(body, "capacity")));
        }
        if (create || body.containsKey("tags")) {
            p.setTags(joinTags(BodyReader.strList(body, "tags")));
        }
        if (create || body.containsKey("data_origin")) {
            String origin = BodyReader.str(body, "data_origin");
            // 运营录入的默认算公开资料（他录的是自己负责的真实资源）；
            // data_origin 描述的是"资料本身公开还是仿真"，与 source 的
            // "从哪个渠道进来"是两件事，不要混用。
            p.setDataOrigin(StringUtils.hasText(origin) ? origin : "PUBLIC");
        }
    }

    /** 农产品字段。语义同 {@link #applyPoi} */
    private void applyProduct(Product p, Map<String, Object> body, boolean create) {
        if (create || body.containsKey("name")) {
            String name = BodyReader.str(body, "name");
            if (!StringUtils.hasText(name)) {
                throw new BizException(ErrorCode.RESOURCE_NAME_REQUIRED);
            }
            p.setName(name.trim());
        }

        if (create || body.containsKey("category_code")) {
            String code = BodyReader.str(body, "category_code");
            if (!StringUtils.hasText(code)) {
                throw new BizException(ErrorCode.RESOURCE_FIELD_INVALID, "请选择农产品分类");
            }
            if (productCategoryMapper.selectById(code) == null) {
                throw new BizException(ErrorCode.RESOURCE_FIELD_INVALID, "分类不存在：" + code);
            }
            p.setCategoryCode(code);
        }

        if (create || body.containsKey("spec")) p.setSpec(BodyReader.str(body, "spec"));
        if (create || body.containsKey("origin_village")) p.setOriginVillage(BodyReader.str(body, "origin_village"));
        if (create || body.containsKey("story")) p.setStory(BodyReader.str(body, "story"));
        if (create || body.containsKey("scene")) p.setScene(BodyReader.str(body, "scene"));
        if (create || body.containsKey("source_url")) p.setSourceUrl(BodyReader.str(body, "source_url"));

        if (create || body.containsKey("price")) {
            p.setPrice(zeroIfNull(BodyReader.decimal(body, "price")));
        }
        if (create || body.containsKey("stock")) {
            p.setStock(zeroIfNull(BodyReader.intOf(body, "stock")));
        }
        if (create || body.containsKey("tags")) {
            p.setTags(joinTags(BodyReader.strList(body, "tags")));
        }
        if (create || body.containsKey("data_origin")) {
            String origin = BodyReader.str(body, "data_origin");
            p.setDataOrigin(StringUtils.hasText(origin) ? origin : "PUBLIC");
        }

        // ---- 挂靠关系 ----
        // 产地与体验锚点都指向**会被数据包重灌的表**，所以这里逐条校验存在性：
        // 库里没有外键，不校验的话，一条指向空气的产品要到详情页才暴露
        // （而且表现为"产地那一栏是空的"，看不出是数据问题）。
        if (create || body.containsKey("poi_id")) {
            String poiId = BodyReader.str(body, "poi_id");
            if (StringUtils.hasText(poiId) && poiMapper.selectById(poiId) == null) {
                throw new BizException(ErrorCode.RESOURCE_FIELD_INVALID, "产地资源点不存在：" + poiId);
            }
            p.setPoiId(StringUtils.hasText(poiId) ? poiId : null);
        }
        if (create || body.containsKey("experience_id")) {
            String expId = BodyReader.str(body, "experience_id");
            if (StringUtils.hasText(expId) && experienceMapper.selectById(expId) == null) {
                throw new BizException(ErrorCode.RESOURCE_FIELD_INVALID, "体验项目不存在：" + expId);
            }
            p.setExperienceId(StringUtils.hasText(expId) ? expId : null);
        }

        // 与 db/V2 的 chk_product_traceable 同一条约束，在这里提前拦下：
        // 让它撞数据库约束的话，运营看到的是 9000「服务内部错误」，
        // 而真正的原因是"这个产品没有出处"。
        if (!StringUtils.hasText(p.getPoiId()) && !StringUtils.hasText(p.getExperienceId())) {
            throw new BizException(ErrorCode.RESOURCE_FIELD_INVALID,
                    "农产品必须挂在产地资源点或体验锚点上，至少填一项");
        }
    }

    // ============================================================
    // 引用检查（删除的硬保护）
    // ============================================================

    /**
     * 一条 poi 被哪些业务数据引用。
     *
     * <p>返回的是**中文类别名**而不是计数，因为它会直接拼进错误提示：
     * "已被历史订单、到访足迹引用"比"存在 3 条引用"有用得多 ——
     * 运营据此知道去哪个页面处理。
     */
    private List<String> poiReferences(String id) {
        List<String> refs = new ArrayList<>();

        // 资源关系。两个方向都要查：一条资源可能既是起点也是终点
        if (poiRelationMapper.selectCount(new LambdaQueryWrapper<PoiRelation>()
                .and(w -> w.eq(PoiRelation::getFromPoiId, id).or().eq(PoiRelation::getToPoiId, id))) > 0) {
            refs.add("资源关系");
        }
        if (poiImageMapper.selectCount(new LambdaQueryWrapper<PoiImage>()
                .eq(PoiImage::getPoiId, id)) > 0) {
            // 配图是磁盘文件 + 库记录的组合，直接删资源会留下没人引用的孤儿文件
            refs.add("配图");
        }
        if (experienceMapper.selectCount(new LambdaQueryWrapper<Experience>()
                .eq(Experience::getPoiId, id)) > 0) {
            refs.add("乡村体验");
        }
        if (productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getPoiId, id)) > 0) {
            refs.add("农产品产地");
        }
        if (tripCheckinMapper.selectCount(new LambdaQueryWrapper<TripCheckin>()
                .eq(TripCheckin::getPoiId, id)) > 0) {
            refs.add("到访足迹");
        }
        // 游客评论（M10 续）。删掉景点会让这些评论变成指向空气的记录：
        // 它们没有 poi 可挂，管理端列表里的"所属景点"会永远显示成编码。
        // 与"配图"同一条理由 —— 都是挂在资源上、删了会留下孤儿的东西。
        if (poiCommentMapper.selectCount(new LambdaQueryWrapper<PoiComment>()
                .eq(PoiComment::getPoiId, id)) > 0) {
            refs.add("游客评论");
        }
        if (orderItemMapper.selectCount(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getPoiId, id)) > 0) {
            refs.add("历史订单");
        }
        return refs;
    }

    /** 一件农产品被哪些业务数据引用。购物车与订单是仅有的两处引用它的地方 */
    private List<String> productReferences(String id) {
        List<String> refs = new ArrayList<>();
        if (cartItemMapper.selectCount(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getProductId, id)) > 0) {
            refs.add("购物车");
        }
        if (orderItemMapper.selectCount(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getProductId, id)) > 0) {
            refs.add("历史订单");
        }
        return refs;
    }

    /**
     * 拒绝改动数据包带来的资源。
     *
     * <p><b>当前只用于删除，以及农产品（product）的编辑。</b>景点（poi）的编辑
     * 已经不走这里了 —— 它改成了"接管"，见 {@link #updatePoi}。
     *
     * <p>不是权限问题，是**这件事对它不成立**：PACK 的行每次启动都会被
     * CityPackImporter 重新灌一遍，改了下一次启动就回去了。与其让运营
     * 改完发现"过两天又变回去了"，不如当场说清替代做法（要改就改数据包）。
     *
     * <p>农产品为什么暂时不做接管：接管一条 PACK 农产品会遇到与景点完全相同
     * 的主键冲突问题（导入器要插同一个 PRD-xxx），改动面一样大。本轮的需求
     * 只点名了景点，就只做景点 —— 不顺手扩大范围。
     */
    private void requireAdminOwned(String id, String name, String source) {
        if (!SOURCE_ADMIN.equals(source)) {
            throw new BizException(ErrorCode.RESOURCE_PACK_LOCKED,
                    "「" + name + "」来自城市数据包，修改后下次启动会被重新导入覆盖，"
                            + "请在 citypack/ 里修改；如只想让游客看不到，请改用下架");
        }
    }

    // ============================================================
    // 查询辅助
    // ============================================================

    private Set<String> businessTypesOf(String type) {
        if (TYPE_SCENIC.equals(type)) {
            return SCENIC_TYPES;
        }
        if (TYPE_FOOD.equals(type)) {
            return Set.of(BIZ_FOOD);
        }
        if (TYPE_LODGING.equals(type)) {
            // 2026-10-04 新增。住宿在 poi 表里本来就有 4 行（business_type=LODGING），
            // 缺的只是这个入口 —— 在它之前传 type=lodging 会掉进下面的 throw
            return Set.of(BIZ_LODGING);
        }
        throw new BizException(ErrorCode.RESOURCE_TYPE_INVALID, "未知的资源类型：" + type);
    }

    private String resolveBusinessType(Map<String, Object> body, Set<String> allowed) {
        String raw = BodyReader.str(body, "business_type");
        if (!StringUtils.hasText(raw)) {
            // 没传就取该入口的主业态：景点入口含两个业态（景区 + 乡村），默认景区；
            // 美食 / 住宿入口各只有一个业态，取那一个。
            // 不用 allowed.iterator().next()：Set.of 的顺序不保证，
            // 那会让"默认值"在不同 JDK 上变成不同的东西。
            if (allowed.contains(BIZ_SCENIC)) {
                return BIZ_SCENIC;
            }
            if (allowed.contains(BIZ_LODGING)) {
                return BIZ_LODGING;
            }
            return BIZ_FOOD;
        }
        String v = raw.trim().toUpperCase();
        if (!allowed.contains(v)) {
            throw new BizException(ErrorCode.RESOURCE_FIELD_INVALID,
                    "该入口下只支持 " + String.join(" / ", allowed) + "，收到 " + raw);
        }
        return v;
    }

    private Poi requirePoi(String id, Set<String> types) {
        Poi p = StringUtils.hasText(id) ? poiMapper.selectById(id) : null;
        // 同城 + 业态匹配一起判：否则 /resources/scenic/{某美食的id} 也能改到它，
        // 等于把"景点管理"变成了一个能操作全部 poi 的后门
        if (p == null || !props.getCity().equals(p.getCityCode()) || !types.contains(p.getBusinessType())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在：" + id);
        }
        return p;
    }

    private Product requireProduct(String id) {
        Product p = StringUtils.hasText(id) ? productMapper.selectById(id) : null;
        if (p == null || !props.getCity().equals(p.getCityCode())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "农产品不存在：" + id);
        }
        return p;
    }

    /** poi_id -> 配图张数。一次查完，不在循环里逐个 count */
    private Map<String, Integer> imageCountsOf(List<String> poiIds) {
        if (poiIds.isEmpty()) {
            return Map.of();
        }
        List<PoiImage> imgs = poiImageMapper.selectList(new LambdaQueryWrapper<PoiImage>()
                .select(PoiImage::getPoiId)
                .in(PoiImage::getPoiId, poiIds));
        return imgs.stream()
                .filter(i -> StringUtils.hasText(i.getPoiId()))
                .collect(Collectors.groupingBy(PoiImage::getPoiId, Collectors.summingInt(i -> 1)));
    }

    private List<String> adminPoiIds() {
        return poiMapper.selectList(new LambdaQueryWrapper<Poi>()
                        .select(Poi::getId)
                        .likeRight(Poi::getId, ID_PREFIX_POI))
                .stream().map(Poi::getId).toList();
    }

    private List<String> adminProductIds() {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .select(Product::getId)
                        .likeRight(Product::getId, ID_PREFIX_PRODUCT))
                .stream().map(Product::getId).toList();
    }

    // ============================================================
    // 转换
    // ============================================================

    private AdminPoiVO toPoiVO(Poi p, Map<String, Integer> imageCounts) {
        AdminPoiVO v = new AdminPoiVO();
        v.setId(p.getId());
        v.setName(p.getName());
        v.setBusinessType(p.getBusinessType());
        v.setDistrict(p.getDistrict());
        v.setAddress(p.getAddress());
        v.setLevel(p.getLevel());
        v.setPhone(p.getPhone());
        v.setLng(VoUtils.toDouble(p.getLng()));
        v.setLat(VoUtils.toDouble(p.getLat()));
        v.setTicketPrice(VoUtils.toDouble(p.getTicketPrice()));
        v.setOpenHours(p.getOpenHours());
        v.setDurationMin(p.getDurationMin());
        v.setCapacity(p.getCapacity());
        v.setWarningThreshold(VoUtils.toDouble(p.getWarningThreshold()));
        v.setTags(VoUtils.splitTags(p.getTags()));
        v.setSummary(p.getSummary());
        v.setDetail(p.getDetail());
        v.setScene(p.getScene());
        v.setDataOrigin(p.getDataOrigin());
        v.setSourceUrl(p.getSourceUrl());
        v.setStatus(p.getStatus());
        v.setSource(p.getSource());
        v.setSourceLabel(sourceLabel(p.getSource()));
        v.setImageCount(imageCounts.getOrDefault(p.getId(), 0));
        v.setCreatedAt(p.getCreatedAt());
        v.setUpdatedAt(p.getUpdatedAt());
        return v;
    }

    private List<AdminProductVO> toProductVOs(List<Product> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        // 名称一次性查出来再分发，不在循环里逐个查（与 ProductServiceImpl 同一做法）
        Map<String, String> poiNames = nameResolver.poiNames(list.stream()
                .map(Product::getPoiId).filter(StringUtils::hasText).collect(Collectors.toSet()));
        Map<String, String> expNames = nameResolver.experienceNames(list.stream()
                .map(Product::getExperienceId).filter(StringUtils::hasText).collect(Collectors.toSet()));
        Map<String, String> catNames = nameResolver.categoryNames();

        List<AdminProductVO> out = new ArrayList<>(list.size());
        for (Product p : list) {
            AdminProductVO v = new AdminProductVO();
            v.setId(p.getId());
            v.setName(p.getName());
            v.setCategoryCode(p.getCategoryCode());
            v.setCategory(catNames.get(p.getCategoryCode()));
            v.setSpec(p.getSpec());
            v.setPrice(VoUtils.toDouble(p.getPrice()));
            v.setOriginVillage(p.getOriginVillage());
            v.setStock(p.getStock());
            v.setTags(VoUtils.splitTags(p.getTags()));
            v.setStory(p.getStory());
            v.setScene(p.getScene());
            v.setPoiId(p.getPoiId());
            v.setPoiName(lookup(poiNames, p.getPoiId()));
            v.setExperienceId(p.getExperienceId());
            v.setExperienceName(lookup(expNames, p.getExperienceId()));
            v.setDataOrigin(p.getDataOrigin());
            v.setSourceUrl(p.getSourceUrl());
            v.setStatus(p.getStatus());
            v.setSource(p.getSource());
            v.setSourceLabel(sourceLabel(p.getSource()));
            v.setCreatedAt(p.getCreatedAt());
            v.setUpdatedAt(p.getUpdatedAt());
            out.add(v);
        }
        return out;
    }

    /** 中文名由服务端给出，前端不维护映射表（与 M6 的 sourceLabel 同一约定） */
    private static String sourceLabel(String source) {
        if (SOURCE_ADMIN.equals(source)) {
            return "运营新建";
        }
        if (SOURCE_PACK.equals(source)) {
            return "城市数据包";
        }
        return source;
    }

    // ============================================================
    // 小工具
    // ============================================================

    /**
     * 生成下一个 ADMIN 编码。
     *
     * <p>取"现有最大序号 + 1"而不是"行数 + 1"：删过一条之后，行数会撞上已有的号，
     * 结果是插入主键冲突。这与 M9 的 {@code nextSortOrder} 是同一个理由。
     *
     * <p>后缀不是数字的（手工插库留下的）直接跳过，不参与递增。
     */
    private static String nextAdminId(String prefix, List<String> existingIds) {
        int max = 0;
        for (String id : existingIds) {
            if (id == null || !id.startsWith(prefix)) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // 非数字后缀，跳过
            }
        }
        return prefix + String.format("%03d", max + 1);
    }

    /**
     * 按 id 取名字，id 为 null 时直接返回 null。
     *
     * <p>不能写成 {@code names.get(id)}：id 可能是 null（农产品只要挂体验锚点
     * 就不必挂产地），而 {@code Map.of()} 返回的不可变 Map 在 get(null) 时抛
     * NullPointerException。{@code NameResolver} 那边已经把空返回改成
     * {@code Collections.emptyMap()}，这里再挡一层 —— 在**本模块**，
     * poi_id / experience_id 为 null 是常态而不是边缘情况（运营新建的农产品
     * 经常只挂一边），不该把正确性押在上游 Map 的实现细节上。
     */
    private static String lookup(Map<String, String> names, String id) {
        return id == null ? null : names.get(id);
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String joined = tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    private static java.math.BigDecimal zeroIfNull(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    private static Integer zeroIfNull(Integer v) {
        return v == null ? 0 : v;
    }
}
