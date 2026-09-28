package com.hanyou.brain.citypack;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.GeoUtils;
import com.hanyou.brain.common.RelationType;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.CityProfile;
import com.hanyou.brain.entity.Experience;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiRelation;
import com.hanyou.brain.entity.PoiVisitStat;
import com.hanyou.brain.entity.Product;
import com.hanyou.brain.entity.ProductCategory;
import com.hanyou.brain.mapper.CityProfileMapper;
import com.hanyou.brain.mapper.ExperienceMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.PoiRelationMapper;
import com.hanyou.brain.mapper.PoiVisitStatMapper;
import com.hanyou.brain.mapper.ProductCategoryMapper;
import com.hanyou.brain.mapper.ProductMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * City Pack 导入器：把 citypack/&lt;city&gt;/ 下的 JSON 灌进数据库，并重建资源关系网络。
 *
 * <p>这是"换城市零改代码"的落点——迁移一个城市只需要放一份新的数据包、改
 * 环境变量 CITY_PACK，代码一行不动。因此这里不允许出现任何汉中专属的硬编码。
 *
 * <p>关系网络是算出来的，不是手填的：按球面距离和业态规则生成 NEARBY /
 * SUPPORT / SAME_VILLAGE / DIVERSION 四类边。换一份数据，网络自动重建。
 *
 * <p>导入前会做引用自检（{@link #validateReferences}）：体验挂的乡村、产品挂的
 * 体验与分类，都必须在同一份数据包里真实存在。宁可启动失败并指出是哪一条错了，
 * 也不要让库里出现一批指向空气的产品——那种问题要到演示时才被发现。
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class CityPackImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CityPackImporter.class);

    /** CityPack JSON 就是 snake_case，用独立的 ObjectMapper，不依赖 Spring 容器的命名策略 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    // ---- 关系生成规则。阈值是设计选择，不是实测值 ----

    /** NEARBY 半径与每个资源的连边上限 */
    private static final double NEARBY_KM = 15;
    private static final int NEARBY_MAX = 6;

    /** 乡村点成片分布，同片区半径放宽 */
    private static final double VILLAGE_KM = 30;
    private static final int VILLAGE_MAX = 4;

    /** 吃住行配套半径，每种目标业态各取最近的几个 */
    private static final double SUPPORT_KM = 20;
    private static final int SUPPORT_PER_TYPE_MAX = 2;

    /** 分流承接半径：热点景区可以把客流引到多远之外的乡村 */
    private static final double DIVERSION_KM = 90;
    private static final int DIVERSION_MAX = 5;

    /** 通行时间估算速度（km/h）。乡村道路限速低于高速，取保守值 */
    private static final double SPEED_KMH = 40;

    private static final String TYPE_SCENIC = "SCENIC";
    private static final String TYPE_RURAL = "RURAL_SPOT";

    /**
     * 本导入器写入的行都标 PACK（M10）。
     *
     * <p>与之相对的是 ADMIN —— 运营在管理端新建的资源。删除条件据此收窄为
     * {@code city_code = ? AND source = 'PACK'}，否则每次重启都会把运营新建的
     * 景点/美食/农产品一起清掉。详见 db/V11__m10_resource_admin.sql 文件头。
     */
    private static final String SOURCE_PACK = "PACK";

    /**
     * 运营在管理端新建、或从数据包**接管**过来的资源（M10 续）。
     *
     * <p>导入器**只写 PACK**，这里出现 ADMIN 纯粹是为了读：判断哪些 id
     * 已经被接管、插入数据包资源时要跳过它们。见 {@link #takenOverPoiIds}。
     */
    private static final String SOURCE_ADMIN = "ADMIN";

    /**
     * 配套型业态：吃、住、行、购。
     *
     * <p>景区与乡村是"目的地"，不作为配套关系的目标——否则详情页的
     * "同一片区域里的吃住行"里会混进乡村点，文案和内容对不上。
     * 景区与乡村之间的关联由 DIVERSION 承担，景区之间由 NEARBY 承担。
     */
    private static final Set<String> SUPPORT_TYPES = Set.of("FOOD", "LODGING", "TRANSPORT", "SHOPPING");

    private final HanYouProperties props;
    private final CityProfileMapper cityProfileMapper;
    private final PoiMapper poiMapper;
    private final PoiRelationMapper poiRelationMapper;
    private final PoiVisitStatMapper poiVisitStatMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final ExperienceMapper experienceMapper;
    private final ProductMapper productMapper;

    /**
     * 启动时导入。
     *
     * <p>这里能直接标 @Transactional：run() 是接口方法，Spring 通过代理对象调用它，
     * 事务会正常生效。导入要么整体成功、要么整体回滚，不会留下"资源导了一半"的库。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.isImportOnStartup()) {
            log.info("[CityPack] hanyou.import-on-startup=false，跳过导入");
            return;
        }

        Path dir = resolveCitypackDir();
        String cityCode = props.getCity();

        // ---- 读取。顺序有依赖：产品要按分类名去查分类编码，所以分类必须先读 ----
        CityProfile profile = readMeta(dir, cityCode);
        List<Poi> pois = readPois(dir, cityCode);
        if (pois.isEmpty()) {
            throw new BizException(ErrorCode.CITYPACK_INVALID, "数据包里没有任何资源点：" + dir.resolve("pois.json"));
        }
        List<ProductCategory> categories = readCategories(dir, cityCode);
        List<Experience> experiences = readExperiences(dir, cityCode);

        // 先把数据包自身的问题挑出来，再动数据库。
        // 问题收集在一个列表里而不是抛一个报一个：数据包是手工维护的，
        // 一次只报一条会让人来回改好几轮。
        List<String> problems = new ArrayList<>();
        Map<String, String> nameToCode = nameToCode(categories, problems);
        List<Product> products = readProducts(dir, cityCode, nameToCode, problems);
        // 客流统计要按 capacity 算占用率，所以放在 pois 之后读
        List<PoiVisitStat> visitStats = readVisitStats(dir, cityCode, pois, problems);
        validateReferences(pois, categories, experiences, products, problems);
        failIfAny(problems, dir);

        // ★ 重灌前先记下"被运营下架过"的行（M10）。
        //
        // `status` **不在数据包里** —— citypack/hanzhong/pois.json 里没有这个字段。
        // 所以"重灌时一律置 1"等于**把运营的下架动作撤销掉**。而数据包资源既不能删、
        // 也不能改字段（见 AdminResourceServiceImpl.requireAdminOwned），
        // **下架是运营唯一能让它从游客端消失的手段** —— 撤销它，这条手段就形同虚设。
        // 因此这三张表的 status 按"运营态"处理，而不是"数据包态"。
        Map<String, Integer> offlinePois = offlinePoiStatuses(cityCode);
        Map<String, Integer> offlineExperiences = offlineExperienceStatuses(cityCode);
        Map<String, Integer> offlineProducts = offlineProductStatuses(cityCode);

        // ★ 已被管理员接管的资源 id（M10 续）。插入前要跳过它们，见下面的说明。
        Set<String> takenOverPoiIds = takenOverPoiIds(cityCode);

        // 数据包是权威来源：每次启动全量重建，避免上一次导入的残留混进来。
        // 删除顺序与依赖相反——先删下游的产品，再删它依赖的体验与分类。
        //
        // ★ poi / experience / product 三张表只删 source='PACK' 的行（M10）。
        // 运营在管理端新建的资源标的是 ADMIN，它们不在数据包里，重灌时也就
        // 不该被当成"上一次导入的残留"清掉 —— 否则运营加一个景点，重启一次
        // 就没了。收窄后的条件与收窄前删的是同一批行（库里其余全是 PACK），
        // 所以对现有数据包行为完全不变。
        //
        // 另外四张表**不加**这个条件，是刻意的：
        //   product_category 是数据包的字典表，没有"人工新建分类"这回事；
        //   poi_relation / poi_visit_stat / city_profile 都是**算出来的投影**
        //   （关系网络、客流统计、城市档案），全量重建正是它们的正确行为。
        productMapper.delete(new LambdaQueryWrapper<Product>()
                .eq(Product::getCityCode, cityCode)
                .eq(Product::getSource, SOURCE_PACK));
        experienceMapper.delete(new LambdaQueryWrapper<Experience>()
                .eq(Experience::getCityCode, cityCode)
                .eq(Experience::getSource, SOURCE_PACK));
        productCategoryMapper.delete(new LambdaQueryWrapper<ProductCategory>().eq(ProductCategory::getCityCode, cityCode));
        poiRelationMapper.delete(new LambdaQueryWrapper<PoiRelation>().eq(PoiRelation::getCityCode, cityCode));
        poiVisitStatMapper.delete(new LambdaQueryWrapper<PoiVisitStat>().eq(PoiVisitStat::getCityCode, cityCode));
        poiMapper.delete(new LambdaQueryWrapper<Poi>()
                .eq(Poi::getCityCode, cityCode)
                .eq(Poi::getSource, SOURCE_PACK));
        cityProfileMapper.deleteById(cityCode);

        // 插入顺序与依赖一致：资源点 -> 分类 -> 体验 -> 产品 -> 关系 -> 客流统计
        cityProfileMapper.insert(profile);
        // ★ 跳过已被管理员接管的 id（M10 续）。
        //
        // 一条 PACK 资源被运营编辑过之后 source 已变成 ADMIN（见
        // AdminResourceServiceImpl.updatePoi），所以上面那句删除**不会删它**，
        // 而数据包里它还在。若这里照样 insert，就是往一个已存在的主键上再插一次，
        // 整个启动事务回滚 —— 表现为"改了某个景点之后后端再也起不来"。
        //
        // 跳过之后这条资源**完全归运营管**：数据包后续更新了它的名字/简介，
        // 也不会再同步到这一行。这正是"接管"这个动作的应有之义 ——
        // 运营改过的东西不能被静默覆盖回去。
        pois.stream()
                .filter(p -> !takenOverPoiIds.contains(p.getId()))
                .forEach(poiMapper::insert);
        categories.forEach(productCategoryMapper::insert);
        experiences.forEach(experienceMapper::insert);
        products.forEach(productMapper::insert);

        // 把运营设过的下架状态写回去（M10，见上面 offlinePoiStatuses 的说明）。
        // 为什么不在插入时直接写对状态：那要给三个 readXxx 各加一个 map 参数，
        // 而它们现在是纯函数（只读 JSON、不碰库）。整个 run() 在一个事务里，
        // 中间不会有人看见这些行短暂回到"上架"。
        offlinePois.forEach((id, status) -> {
            Poi patch = new Poi();
            patch.setId(id);
            patch.setStatus(status);
            poiMapper.updateById(patch);
        });
        offlineExperiences.forEach((id, status) -> {
            Experience patch = new Experience();
            patch.setId(id);
            patch.setStatus(status);
            experienceMapper.updateById(patch);
        });
        offlineProducts.forEach((id, status) -> {
            Product patch = new Product();
            patch.setId(id);
            patch.setStatus(status);
            productMapper.updateById(patch);
        });

        List<PoiRelation> relations = buildRelations(cityCode, pois);
        relations.forEach(poiRelationMapper::insert);

        visitStats.forEach(poiVisitStatMapper::insert);

        log.info("[CityPack] 导入完成 city={} 资源点={} 关系={} 分类={} 体验={} 产品={} 客流统计={} 数据目录={}",
                cityCode, pois.size(), relations.size(), categories.size(),
                experiences.size(), products.size(), visitStats.size(), dir);
    }

    /**
     * 读出一张表里**被运营下架过**的行（{@code status != 1}），用于重灌后还原。
     *
     * <p>为什么只挑 {@code status != 1}：上架是默认值，绝大多数行都是 1，
     * 把它们也读出来再逐条 UPDATE 是白做。只还原"少数被改过的"，
     * 一次重启多出的写操作量与运营实际下架过的条数同阶。
     *
     * <p>三类资源各写一个方法而不是抽成一个泛型：MyBatis-Plus 的 lambda 列引用
     * （{@code SFunction}）在泛型里传参需要把 5 个列引用都当参数塞进来，
     * 读起来比三份直白的代码更难对。这里重复是有意的。
     */
    private Map<String, Integer> offlinePoiStatuses(String cityCode) {
        return poiMapper.selectList(new LambdaQueryWrapper<Poi>()
                        .eq(Poi::getCityCode, cityCode)
                        .eq(Poi::getSource, SOURCE_PACK)
                        .ne(Poi::getStatus, 1))
                .stream()
                .collect(Collectors.toMap(Poi::getId, Poi::getStatus));
    }

    private Map<String, Integer> offlineExperienceStatuses(String cityCode) {
        return experienceMapper.selectList(new LambdaQueryWrapper<Experience>()
                        .eq(Experience::getCityCode, cityCode)
                        .eq(Experience::getSource, SOURCE_PACK)
                        .ne(Experience::getStatus, 1))
                .stream()
                .collect(Collectors.toMap(Experience::getId, Experience::getStatus));
    }

    private Map<String, Integer> offlineProductStatuses(String cityCode) {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getCityCode, cityCode)
                        .eq(Product::getSource, SOURCE_PACK)
                        .ne(Product::getStatus, 1))
                .stream()
                .collect(Collectors.toMap(Product::getId, Product::getStatus));
    }

    /**
     * 已被管理员接管的资源 id（M10 续）。
     *
     * <p>取 {@code source = 'ADMIN'} 的全部 id。这个集合里有两类东西，
     * 放在一起只是省一次查询：
     * <ul>
     *   <li>运营在管理端新建的资源（id 形如 P-ADM-001）—— 数据包里没有这些 id，
     *       过滤掉它们不产生任何影响；</li>
     *   <li>从数据包**接管**来的资源（id 仍是 P-SCE-001）—— <b>这一类才是关键</b>：
     *       数据包里还有同 id 的行，不跳过就会主键冲突。</li>
     * </ul>
     *
     * <p>只查 poi、不查 experience / product：本轮只做了景点的接管
     * （见 AdminResourceServiceImpl.requireAdminOwned 的注释）。那两张表当前
     * 不可能出现"ADMIN 行与数据包同 id"的情形，所以没有这个问题 ——
     * 真要做的时候，这里要一起加，否则会以完全相同的形态炸掉。
     */
    private Set<String> takenOverPoiIds(String cityCode) {
        return poiMapper.selectList(new LambdaQueryWrapper<Poi>()
                        .select(Poi::getId)
                        .eq(Poi::getCityCode, cityCode)
                        .eq(Poi::getSource, SOURCE_ADMIN))
                .stream()
                .map(Poi::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 定位数据包目录。
     *
     * <p>相对路径以进程工作目录为基准：开发期从 server-java/ 启动（../citypack），
     * 打包后可能从仓库根启动（./citypack）。两种都试一遍，
     * 免得换个启动方式就报"数据包不存在"。
     */
    private Path resolveCitypackDir() {
        String configured = props.getCitypackDir();
        Path cwd = Paths.get("").toAbsolutePath();
        Path base = Paths.get(configured);

        Set<Path> candidates = new LinkedHashSet<>();
        if (base.isAbsolute()) {
            candidates.add(base.resolve(props.getCity()));
        } else {
            candidates.add(cwd.resolve(base).normalize().resolve(props.getCity()));
            candidates.add(cwd.resolve("..").resolve(base).normalize().resolve(props.getCity()));
            candidates.add(cwd.resolve("citypack").resolve(props.getCity()));
            candidates.add(cwd.resolve("..").resolve("citypack").resolve(props.getCity()));
        }

        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new BizException(ErrorCode.CITYPACK_MISSING,
                "找不到城市数据包，已尝试：" + candidates.stream().map(Path::toString).collect(Collectors.joining(" | ")));
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private CityProfile readMeta(Path dir, String cityCode) {
        MetaJson m = readJson(dir.resolve("meta.json"), new TypeReference<MetaJson>() {
        });
        CityProfile p = new CityProfile();
        // 以配置的 hanyou.city 为准，不用 JSON 里的 city_code——
        // 否则目录名和字段值不一致时会出现两套"当前城市"
        p.setCityCode(cityCode);
        p.setName(m.getName());
        p.setProvince(m.getProvince());
        if (m.getCenter() != null) {
            p.setCenterLng(toDecimal(m.getCenter().getLng()));
            p.setCenterLat(toDecimal(m.getCenter().getLat()));
        }
        p.setTagline(m.getTagline());
        p.setSummary(m.getSummary());
        p.setDataOrigin(m.getDataOrigin());
        p.setDisclaimer(m.getDisclaimer());
        p.setVersion(m.getVersion());
        return p;
    }

    private List<Poi> readPois(Path dir, String cityCode) {
        List<PoiJson> raw = readJson(dir.resolve("pois.json"), new TypeReference<List<PoiJson>>() {
        });
        List<Poi> out = new ArrayList<>(raw.size());
        for (PoiJson j : raw) {
            Poi p = new Poi();
            p.setId(j.getId());
            p.setCityCode(cityCode);
            p.setName(j.getName());
            p.setBusinessType(j.getBusinessType());
            p.setDistrict(j.getDistrict());
            p.setLevel(j.getLevel());
            p.setLng(toDecimal(j.getLng()));
            p.setLat(toDecimal(j.getLat()));
            p.setTicketPrice(toDecimal(j.getTicketPrice()));
            p.setOpenHours(j.getOpenHours());
            p.setDurationMin(j.getDurationMin());
            p.setCapacity(j.getCapacity());
            // 数组入库为逗号分隔字符串；对外再由 PoiVO 转回数组
            p.setTags(j.getTags() == null ? null : String.join(",", j.getTags()));
            p.setSummary(j.getSummary());
            p.setScene(j.getScene());
            p.setDataOrigin(j.getDataOrigin());
            p.setSourceUrl(j.getSourceUrl());
            p.setStatus(1);
            p.setSource(SOURCE_PACK);
            out.add(p);
        }
        return out;
    }

    private List<ProductCategory> readCategories(Path dir, String cityCode) {
        List<CategoryJson> raw = readJson(dir.resolve("categories.json"), new TypeReference<List<CategoryJson>>() {
        });
        List<ProductCategory> out = new ArrayList<>(raw.size());
        for (CategoryJson j : raw) {
            ProductCategory c = new ProductCategory();
            c.setCode(j.getCode());
            c.setCityCode(cityCode);
            c.setName(j.getName());
            c.setParentCode(j.getParentCode());
            // 排序值缺失时给 0 而不是 null：null 参与 order by 时位置随数据库实现而变，
            // 首页筛选条的顺序会飘
            c.setSort(j.getSort() == null ? 0 : j.getSort());
            out.add(c);
        }
        return out;
    }

    private List<Experience> readExperiences(Path dir, String cityCode) {
        List<ExperienceJson> raw = readJson(dir.resolve("experiences.json"),
                new TypeReference<List<ExperienceJson>>() {
                });
        List<Experience> out = new ArrayList<>(raw.size());
        for (ExperienceJson j : raw) {
            Experience e = new Experience();
            e.setId(j.getId());
            e.setCityCode(cityCode);
            e.setPoiId(j.getPoiId());
            e.setName(j.getName());
            e.setType(j.getType());
            e.setDurationMin(j.getDurationMin());
            e.setPrice(toDecimal(j.getPrice()));
            e.setSeason(j.getSeason());
            e.setCapacity(j.getCapacity());
            // 数组入库为逗号分隔字符串；对外再由 ExperienceVO 转回数组
            e.setTags(j.getTags() == null ? null : String.join(",", j.getTags()));
            // 数据包里叫 desc，库里叫 description（DESC 是 SQL 保留字），对外仍叫 desc
            e.setDescription(j.getDesc());
            e.setDataOrigin(j.getDataOrigin());
            e.setSourceUrl(j.getSourceUrl());
            e.setStatus(1);
            e.setSource(SOURCE_PACK);
            out.add(e);
        }
        return out;
    }

    /**
     * 读取产品，并把分类中文名解析成分类编码。
     *
     * <p>数据包里产品写的是分类名（"茶叶"）而不是编码（"CAT-TEA"）——数据包是给人
     * 维护的，写中文比记编码更不容易错。但库里必须存编码：否则改一次分类名，
     * 所有产品的分类引用就全断了。解析放在导入这一步，把"人可读"和"机器稳定"
     * 两件事分开，代价是分类名成了隐式外键，所以解析不出来时记一条问题，
     * 不允许静默落一条没有分类的产品。
     */
    private List<Product> readProducts(Path dir, String cityCode, Map<String, String> nameToCode,
            List<String> problems) {
        List<ProductJson> raw = readJson(dir.resolve("products.json"), new TypeReference<List<ProductJson>>() {
        });
        List<Product> out = new ArrayList<>(raw.size());

        for (ProductJson j : raw) {
            Product p = new Product();
            p.setId(j.getId());
            p.setCityCode(cityCode);
            p.setPoiId(j.getPoiId());
            p.setExperienceId(j.getExperienceId());
            p.setName(j.getName());
            p.setSpec(j.getSpec());
            p.setPrice(toDecimal(j.getPrice()));
            p.setOriginVillage(j.getOriginVillage());
            p.setStock(j.getStock());
            p.setTags(j.getTags() == null ? null : String.join(",", j.getTags()));
            p.setStory(j.getStory());
            p.setScene(j.getScene());
            p.setDataOrigin(j.getDataOrigin());
            p.setSourceUrl(j.getSourceUrl());
            p.setStatus(1);
            p.setSource(SOURCE_PACK);

            String code = nameToCode.get(j.getCategory());
            if (code == null) {
                problems.add("产品 " + j.getId() + " 的分类「" + j.getCategory() + "」在 categories.json 里没有");
            }
            p.setCategoryCode(code);
            out.add(p);
        }
        return out;
    }

    /**
     * 读取客流与经营日度统计（M5 的规则引擎输入）。
     *
     * <p><b>★ 数据包里存的是"距今天的天数偏移"（offset，0 = 今天），不是绝对日期。</b>
     * 这里把它物化成真实日期。为什么不在数据包里写死日期：绝对日期一旦写进
     * 数据包，跑一个月后"近 7 日"就全过期了 —— 而演示恰恰要反复看"近 7 日"。
     * 偏移由导入时才知道的"今天"来解，所以每次重启导入拿到的都是
     * 与当下对齐的数据，不需要定期重新生成数据包。
     *
     * <p>capacity_usage 在这里算好写入，不让读的人现算：capacity 是"设计承载"，
     * 会随数据包更新而变化；现算的话，历史某天的占用率会跟着今天的 capacity
     * 一起变，那就成了"上个月的数据今天看又是另一个数"。
     *
     * <p>数据包没有 visit_stats.json 时**不阻断启动**（老数据包仍能用），
     * 只记一条 warn。这一步必须在日志里看得见：没有客流数据时 M5 的
     * 规则引擎一条规则都跑不出来，界面会显示"0 条风险"，而那是
     * 数据缺失、不是"一切正常"。
     */
    private List<PoiVisitStat> readVisitStats(Path dir, String cityCode, List<Poi> pois, List<String> problems) {
        Path file = dir.resolve("visit_stats.json");
        if (!Files.isRegularFile(file)) {
            log.warn("[CityPack] 数据包没有 visit_stats.json，跳过客流统计导入；"
                    + "M5 规则引擎将没有输入，风险事件会恒为 0。文件：{}", file);
            return List.of();
        }

        VisitStatsJson pack = readJson(file, new TypeReference<VisitStatsJson>() {
        });
        Map<String, Integer> capacityOf = new HashMap<>();
        for (Poi p : pois) {
            capacityOf.put(p.getId(), p.getCapacity());
        }

        LocalDate today = LocalDate.now();
        List<PoiVisitStat> out = new ArrayList<>();
        for (VisitStatsPoiJson p : pack.getPois()) {
            Integer capacity = capacityOf.get(p.getPoiId());
            if (capacity == null) {
                // 与产品/体验的引用校验同一处理方式：记问题、不静默丢弃
                problems.add("visit_stats.json 里的 poi_id=" + p.getPoiId() + " 在 pois.json 里不存在");
                continue;
            }
            for (VisitStatDayJson d : p.getSeries()) {
                PoiVisitStat s = new PoiVisitStat();
                s.setCityCode(cityCode);
                s.setPoiId(p.getPoiId());
                s.setStatDate(today.plusDays(d.getOffset()));
                s.setVisitors(d.getVisitors());
                s.setCapacityUsage(usageOf(d.getVisitors(), capacity));
                s.setReviewCount(d.getReviewCount());
                s.setNegativeCount(d.getNegativeCount());
                s.setExperienceVisits(d.getExperienceVisits());
                s.setPurchases(d.getPurchases());
                s.setRepurchases(d.getRepurchases());
                s.setSynthetic(true);
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 承载占用率 = 到访 / 设计承载，保留 4 位小数。
     *
     * <p>capacity 缺失或为 0 时返回 0 而不是抛异常：数据包里确实允许
     * 某个资源点没有承载量（如交通枢纽），那不是错误，只是没有承载概念。
     */
    private static BigDecimal usageOf(Integer visitors, Integer capacity) {
        if (visitors == null || capacity == null || capacity <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(visitors).divide(BigDecimal.valueOf(capacity), 4, RoundingMode.HALF_UP);
    }

    /**
     * 分类名 -> 分类编码。
     *
     * <p>重名会让产品指到不确定的分类上，判为数据包错误。这里仍然返回一份 map
     * （重名时先出现的那个胜出）而不是直接抛：让引用校验能继续跑完，
     * 把同一份数据包里的问题一次报全。
     */
    private Map<String, String> nameToCode(List<ProductCategory> categories, List<String> problems) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ProductCategory c : categories) {
            if (!StringUtils.hasText(c.getName())) {
                problems.add("分类 " + c.getCode() + " 没有 name，产品无法按名字引用它");
                continue;
            }
            String previous = map.putIfAbsent(c.getName(), c.getCode());
            if (previous != null) {
                problems.add("categories.json 里有重名分类「" + c.getName() + "」（"
                        + previous + " 与 " + c.getCode() + "），产品无法确定该挂到哪个编码上");
            }
        }
        return map;
    }

    /**
     * 引用自检。
     *
     * <p>数据库没有建外键（跨模块的表还会继续加，外键会让换城市时的全量重建变脆），
     * 所以引用完整性由导入器负责。检查项与 db/V2__m2_experience_product.sql 里
     * chk_product_traceable 约束一致：产品必须挂产地或体验，至少一项。
     *
     * <p>分类名到编码的解析问题由 readProducts / nameToCode 记录，不在这里重复报。
     * 只收集、不抛，由调用方统一决定什么时候失败。
     */
    private void validateReferences(List<Poi> pois, List<ProductCategory> categories,
            List<Experience> experiences, List<Product> products, List<String> problems) {
        Set<String> poiIds = pois.stream().map(Poi::getId).collect(Collectors.toSet());
        Set<String> experienceIds = experiences.stream().map(Experience::getId).collect(Collectors.toSet());
        Set<String> categoryCodes = categories.stream().map(ProductCategory::getCode).collect(Collectors.toSet());

        for (Experience e : experiences) {
            if (!StringUtils.hasText(e.getPoiId()) || !poiIds.contains(e.getPoiId())) {
                problems.add("体验 " + e.getId() + " 的 poi_id=" + e.getPoiId() + " 在 pois.json 里不存在");
            }
        }

        for (Product p : products) {
            boolean hasPoi = StringUtils.hasText(p.getPoiId());
            boolean hasExperience = StringUtils.hasText(p.getExperienceId());

            if (!hasPoi && !hasExperience) {
                problems.add("产品 " + p.getId() + " 既没有 poi_id 也没有 experience_id（产品必须可追溯）");
            }
            if (hasPoi && !poiIds.contains(p.getPoiId())) {
                problems.add("产品 " + p.getId() + " 的 poi_id=" + p.getPoiId() + " 在 pois.json 里不存在");
            }
            if (hasExperience && !experienceIds.contains(p.getExperienceId())) {
                problems.add("产品 " + p.getId() + " 的 experience_id=" + p.getExperienceId()
                        + " 在 experiences.json 里不存在");
            }
            // categoryCode 为 null 的情况已由 readProducts 报过，这里不重复
            if (p.getCategoryCode() != null && !categoryCodes.contains(p.getCategoryCode())) {
                problems.add("产品 " + p.getId() + " 的分类编码 " + p.getCategoryCode() + " 在 categories.json 里不存在");
            }
        }
    }

    /** 有问题就一次性全抛出来，附带数据包路径，让人知道该去改哪个目录 */
    private void failIfAny(List<String> problems, Path dir) {
        if (problems.isEmpty()) {
            return;
        }
        throw new BizException(ErrorCode.CITYPACK_INVALID,
                "数据包校验未通过（" + dir + "），共 " + problems.size() + " 处：\n  - "
                        + String.join("\n  - ", problems));
    }

    private <T> T readJson(Path file, TypeReference<T> type) {
        if (!Files.isRegularFile(file)) {
            throw new BizException(ErrorCode.CITYPACK_MISSING, "缺少数据文件：" + file);
        }
        try {
            return JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new BizException(ErrorCode.CITYPACK_INVALID, "解析失败：" + file + " —— " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 关系网络
    // ------------------------------------------------------------------

    /**
     * 生成资源关系。对每个资源算出到其余资源的距离，再按四类规则各取一批。
     *
     * <p>一对资源可以同时属于多种关系（既邻近又是配套），这是刻意的——
     * 关系是多维的，表上的唯一键是 (from, to, type)，允许这种重叠。
     */
    private List<PoiRelation> buildRelations(String cityCode, List<Poi> pois) {
        List<PoiRelation> out = new ArrayList<>();

        for (Poi from : pois) {
            if (from.getLng() == null || from.getLat() == null) {
                continue;
            }

            // 到其余所有点的距离算一次，四类规则共用，避免重复计算
            List<Candidate> all = new ArrayList<>();
            for (Poi to : pois) {
                if (to.getId().equals(from.getId()) || to.getLng() == null || to.getLat() == null) {
                    continue;
                }
                all.add(new Candidate(to, GeoUtils.haversineKm(from, to)));
            }

            // NEARBY：最近的几个，不分业态
            all.stream()
                    .filter(c -> c.km <= NEARBY_KM)
                    .sorted(Comparator.comparingDouble(c -> c.km))
                    .limit(NEARBY_MAX)
                    .forEach(c -> out.add(relation(cityCode, from, c, RelationType.NEARBY)));

            // SAME_VILLAGE：乡村点之间的片区联动
            if (TYPE_RURAL.equals(from.getBusinessType())) {
                all.stream()
                        .filter(c -> TYPE_RURAL.equals(c.poi.getBusinessType()) && c.km <= VILLAGE_KM)
                        .sorted(Comparator.comparingDouble(c -> c.km))
                        .limit(VILLAGE_MAX)
                        .forEach(c -> out.add(relation(cityCode, from, c, RelationType.SAME_VILLAGE)));
            }

            // SUPPORT：吃住行购的配套，每种目标业态各取最近的几个
            Map<String, List<Candidate>> byType = all.stream()
                    .filter(c -> SUPPORT_TYPES.contains(c.poi.getBusinessType())
                            && !c.poi.getBusinessType().equals(from.getBusinessType())
                            && c.km <= SUPPORT_KM)
                    .collect(Collectors.groupingBy(c -> c.poi.getBusinessType()));
            byType.values().forEach(list -> list.stream()
                    .sorted(Comparator.comparingDouble(c -> c.km))
                    .limit(SUPPORT_PER_TYPE_MAX)
                    .forEach(c -> out.add(relation(cityCode, from, c, RelationType.SUPPORT))));

            // DIVERSION：景区 -> 乡村。"客流下乡"在数据层就落在这批边上
            if (TYPE_SCENIC.equals(from.getBusinessType())) {
                all.stream()
                        .filter(c -> TYPE_RURAL.equals(c.poi.getBusinessType()) && c.km <= DIVERSION_KM)
                        .sorted(Comparator.comparingDouble(c -> c.km))
                        .limit(DIVERSION_MAX)
                        .forEach(c -> out.add(relation(cityCode, from, c, RelationType.DIVERSION)));
            }
        }
        return out;
    }

    private PoiRelation relation(String cityCode, Poi from, Candidate c, String type) {
        PoiRelation r = new PoiRelation();
        r.setCityCode(cityCode);
        r.setFromPoiId(from.getId());
        r.setToPoiId(c.poi.getId());
        r.setRelationType(type);
        r.setDistanceKm(BigDecimal.valueOf(c.km).setScale(2, RoundingMode.HALF_UP));
        r.setTravelMin((int) Math.max(1, Math.round(c.km / SPEED_KMH * 60)));
        // 越近权重越高，映射到 [0.10, 1.00]
        double w = Math.max(0.10, 1.0 / (1.0 + c.km / 10.0));
        r.setWeight(BigDecimal.valueOf(w).setScale(2, RoundingMode.HALF_UP));
        return r;
    }

    /**
     * Haversine 距离已抽到 {@link GeoUtils} —— M5 的分流候选排序要用同一个公式，
     * 两处各抄一份必然会漂移（详情页说 12.3 km、公告说 12.5 km 就会被当成 bug）。
     * 这里不再保留私有副本。
     */
    private static BigDecimal toDecimal(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    // ------------------------------------------------------------------
    // CityPack JSON 的镜像结构（仅导入时使用）
    // ------------------------------------------------------------------

    @Data
    private static class MetaJson {
        private String cityCode;
        private String name;
        private String province;
        private CenterJson center;
        private String tagline;
        private String summary;
        private String dataOrigin;
        private String disclaimer;
        private String version;
    }

    @Data
    private static class CenterJson {
        private Double lng;
        private Double lat;
    }

    @Data
    private static class PoiJson {
        private String id;
        private String name;
        private String businessType;
        private String district;
        private String level;
        private Double lng;
        private Double lat;
        private Double ticketPrice;
        private String openHours;
        private Integer durationMin;
        private Integer capacity;
        private List<String> tags;
        private String summary;
        private String scene;
        private String dataOrigin;
        private String sourceUrl;
    }

    @Data
    private static class CategoryJson {
        private String code;
        private String name;
        private String parentCode;
        private Integer sort;
    }

    @Data
    private static class ExperienceJson {
        private String id;
        private String poiId;
        private String name;
        private String type;
        private Integer durationMin;
        private Double price;
        private String season;
        private Integer capacity;
        private List<String> tags;

        /** 数据包里叫 desc，落库到 Experience.description（DESC 是 SQL 保留字） */
        private String desc;

        private String dataOrigin;
        private String sourceUrl;
    }

    @Data
    private static class ProductJson {
        private String id;
        private String poiId;
        private String experienceId;

        /** 分类中文名，由 readProducts 解析成 categoryCode */
        private String category;

        private String name;
        private String spec;
        private Double price;
        private String originVillage;
        private Integer stock;
        private List<String> tags;
        private String story;
        private String scene;
        private String dataOrigin;
        private String sourceUrl;
    }

    /**
     * visit_stats.json 的镜像结构（M5）。
     *
     * <p>顶层带 synthetic / generator / note 三个说明字段，是**刻意保留**的：
     * 它们让"这份数据是仿真出来的"写在数据文件本身里，而不是只写在文档里。
     * 解析时用不到，但把文件交给别人看时一眼就能看到。
     */
    @Data
    private static class VisitStatsJson {
        private Boolean synthetic;
        private String generator;
        private String note;
        private Integer days;
        private List<VisitStatsPoiJson> pois;
    }

    @Data
    private static class VisitStatsPoiJson {
        private String poiId;
        private String businessType;
        private Integer capacity;
        private Double baseUsage;
        private List<VisitStatDayJson> series;
    }

    /** 一天的统计。offset 是"距导入日的天数偏移"，0 = 今天 */
    @Data
    private static class VisitStatDayJson {
        private Integer offset;
        private Integer visitors;
        private Integer reviewCount;
        private Integer negativeCount;
        private Integer experienceVisits;
        private Integer purchases;
        private Integer repurchases;
    }

    /** 候选目标 + 到它的距离 */
    @Data
    @AllArgsConstructor
    private static class Candidate {
        private Poi poi;
        private double km;
    }
}
