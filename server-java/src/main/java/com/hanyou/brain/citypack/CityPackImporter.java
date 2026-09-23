package com.hanyou.brain.citypack;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.RelationType;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.CityProfile;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiRelation;
import com.hanyou.brain.mapper.CityProfileMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.PoiRelationMapper;

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
 */
@Component
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

        CityProfile profile = readMeta(dir, cityCode);
        List<Poi> pois = readPois(dir, cityCode);
        if (pois.isEmpty()) {
            throw new BizException(ErrorCode.CITYPACK_INVALID, "数据包里没有任何资源点：" + dir.resolve("pois.json"));
        }

        // 数据包是权威来源：每次启动全量重建，避免上一次导入的残留混进来
        poiRelationMapper.delete(new LambdaQueryWrapper<PoiRelation>().eq(PoiRelation::getCityCode, cityCode));
        poiMapper.delete(new LambdaQueryWrapper<Poi>().eq(Poi::getCityCode, cityCode));
        cityProfileMapper.deleteById(cityCode);

        cityProfileMapper.insert(profile);
        pois.forEach(poiMapper::insert);

        List<PoiRelation> relations = buildRelations(cityCode, pois);
        relations.forEach(poiRelationMapper::insert);

        log.info("[CityPack] 导入完成 city={} 资源点={} 关系={} 数据目录={}",
                cityCode, pois.size(), relations.size(), dir);
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
            out.add(p);
        }
        return out;
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
                all.add(new Candidate(to, haversineKm(from, to)));
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

    /** Haversine 球面距离（公里）。经纬度是估算值，距离保留到 0.01km 已足够 */
    private static double haversineKm(Poi a, Poi b) {
        double r = 6371.0;
        double lat1 = a.getLat().doubleValue();
        double lng1 = a.getLng().doubleValue();
        double lat2 = b.getLat().doubleValue();
        double lng2 = b.getLng().doubleValue();

        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(s));
    }

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

    /** 候选目标 + 到它的距离 */
    @Data
    @AllArgsConstructor
    private static class Candidate {
        private Poi poi;
        private double km;
    }
}
