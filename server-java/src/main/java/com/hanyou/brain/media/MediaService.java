package com.hanyou.brain.media;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiImage;
import com.hanyou.brain.entity.SiteBanner;
import com.hanyou.brain.mapper.PoiImageMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.SiteBannerMapper;
import com.hanyou.brain.vo.BannerVO;
import com.hanyou.brain.vo.PoiImageVO;

import lombok.RequiredArgsConstructor;

/**
 * 媒体与配图业务（M9）。
 *
 * <p><b>删除与替换的顺序原则：先动数据库，再动文件。</b>
 * 反过来的话，文件已经删了而事务回滚，库里就留下一行指向不存在文件的记录 ——
 * 表现为页面上一个破图，且很难查。而按现在的顺序，最坏情况是磁盘上多一个
 * 没人引用的孤儿文件，无害且可以用脚本清理。
 *
 * <p><b>封面（isCover）的不变量：同一 poi_id 下至多一张为 1。</b>
 * 这个不变量在数据库层没有约束（MySQL 的部分唯一索引写法不通用），
 * 所以由本类在每次写封面时统一维护，且只在这一处维护。
 */
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final PoiImageMapper imageMapper;
    private final SiteBannerMapper bannerMapper;
    private final PoiMapper poiMapper;
    private final MediaStorageService storage;

    // ============================================================
    // 读：景点配图
    // ============================================================

    /** 全部景点配图，按 poi_id + sort_order 排序。前端一次取回自己按 poi_id 分组 */
    public List<PoiImageVO> listAllPoiImages() {
        return imageMapper
                .selectList(Wrappers.<PoiImage>lambdaQuery()
                        .orderByAsc(PoiImage::getPoiId)
                        .orderByAsc(PoiImage::getSortOrder)
                        .orderByAsc(PoiImage::getId))
                .stream()
                .map(PoiImageVO::from)
                .toList();
    }

    /** 某个景点的配图 */
    public List<PoiImageVO> listPoiImages(String poiId) {
        return imageMapper
                .selectList(Wrappers.<PoiImage>lambdaQuery()
                        .eq(PoiImage::getPoiId, poiId)
                        .orderByAsc(PoiImage::getSortOrder)
                        .orderByAsc(PoiImage::getId))
                .stream()
                .map(PoiImageVO::from)
                .toList();
    }

    /**
     * 每个景点的封面图，返回 poi_id -> url。
     *
     * <p>游客端的列表页只需要这一份映射：一次请求拿到所有封面，
     * 避免每张卡片各发一次请求。没有配图的景点不在返回值里，
     * 前端查不到就回落到手写 SVG。
     */
    public Map<String, String> coverMap() {
        // 只查一次库。分成"先收封面、再兜底"两轮循环，是因为 HashMap 里
        // 同一 poi_id 的先后顺序无法保证 —— 一轮循环的话，后到的普通图
        // 可能把先到的封面覆盖掉，而封面恰恰是运营明确指定过的那一张。
        List<PoiImageVO> all = listAllPoiImages();

        Map<String, String> out = new HashMap<>();
        for (PoiImageVO v : all) {
            if (v.getIsCover() != null && v.getIsCover() == 1) {
                out.putIfAbsent(v.getPoiId(), v.getUrl());
            }
        }
        // 有图但没标封面的（历史数据或手工插库）用列表里的第一张兜底，
        // 否则运营会看到"明明传了图，卡片还是插画"这种难解释的现象
        for (PoiImageVO v : all) {
            out.putIfAbsent(v.getPoiId(), v.getUrl());
        }
        return out;
    }

    // ============================================================
    // 写：景点配图
    // ============================================================

    /**
     * 给某个景点新增一张配图。
     *
     * <p>该景点原本一张图都没有时，自动把新图设为封面 —— 否则运营传完图
     * 回到列表页会发现卡片还是插画，得再点一次"设为封面"才生效，很反直觉。
     */
    @Transactional
    public PoiImageVO uploadPoiImage(String poiId, MultipartFile file, String altText) {
        requirePoi(poiId);

        List<PoiImage> existing = imageMapper.selectList(
                Wrappers.<PoiImage>lambdaQuery().eq(PoiImage::getPoiId, poiId));

        String path = storage.save(file, "poi/" + poiId);

        PoiImage e = new PoiImage();
        e.setPoiId(poiId);
        e.setImagePath(path);
        e.setAltText(altText);
        e.setSortOrder(nextSortOrder(poiId));
        e.setIsCover(existing.isEmpty() ? 1 : 0);
        e.setSource("UPLOAD");
        imageMapper.insert(e);

        log.info("[M9] 新增景点配图 poiId={} id={} path={}", poiId, e.getId(), path);
        return PoiImageVO.from(imageMapper.selectById(e.getId()));
    }

    /**
     * 替换某张配图的文件，保留它的排序与封面状态。
     *
     * <p>与"删掉再传一张"的区别就在这里：运营只是想换张照片，
     * 不应该顺带把它在列表里的位置和封面身份一起弄丢。
     */
    @Transactional
    public PoiImageVO replacePoiImage(Long imageId, MultipartFile file) {
        PoiImage old = requireImage(imageId);
        String oldPath = old.getImagePath();

        // 先落新文件：这一步失败就整个失败，数据库与磁盘都还是原样
        String newPath = storage.save(file, "poi/" + old.getPoiId());

        PoiImage patch = new PoiImage();
        patch.setId(imageId);
        patch.setImagePath(newPath);
        imageMapper.updateById(patch);

        // 数据库已改完，再删旧文件。删失败只留个孤儿文件，不影响正确性
        storage.delete(oldPath);

        log.info("[M9] 替换景点配图 id={} {} -> {}", imageId, oldPath, newPath);
        return PoiImageVO.from(imageMapper.selectById(imageId));
    }

    /** 改备注 / 设为封面 */
    @Transactional
    public PoiImageVO updatePoiImage(Long imageId, String altText, Integer isCover) {
        PoiImage old = requireImage(imageId);

        PoiImage patch = new PoiImage();
        patch.setId(imageId);
        if (altText != null) patch.setAltText(altText);
        if (isCover != null) {
            patch.setIsCover(isCover == 1 ? 1 : 0);
        }
        imageMapper.updateById(patch);

        // 设为封面时把同景点其它图取消封面，维持"至多一张封面"的不变量
        if (isCover != null && isCover == 1) {
            clearOtherCovers(old.getPoiId(), imageId);
        }
        return PoiImageVO.from(imageMapper.selectById(imageId));
    }

    /**
     * 删除一张配图。
     *
     * <p>若删掉的正是封面，自动把剩下的第一张提为封面 ——
     * 不然列表页会突然从实拍图退回插画，看起来像"图丢了"。
     */
    @Transactional
    public void deletePoiImage(Long imageId) {
        PoiImage img = requireImage(imageId);
        boolean wasCover = img.getIsCover() != null && img.getIsCover() == 1;

        // 先删库，再删文件（顺序理由见类注释）
        imageMapper.deleteById(imageId);
        storage.delete(img.getImagePath());

        if (wasCover) {
            List<PoiImage> rest = imageMapper.selectList(Wrappers.<PoiImage>lambdaQuery()
                    .eq(PoiImage::getPoiId, img.getPoiId())
                    .orderByAsc(PoiImage::getSortOrder)
                    .orderByAsc(PoiImage::getId));
            if (!rest.isEmpty()) {
                PoiImage promote = new PoiImage();
                promote.setId(rest.get(0).getId());
                promote.setIsCover(1);
                imageMapper.updateById(promote);
                log.info("[M9] 封面被删，自动提升 id={} 为封面", rest.get(0).getId());
            }
        }
        log.info("[M9] 删除景点配图 id={} poiId={}", imageId, img.getPoiId());
    }

    /**
     * 重排某个景点的配图顺序。
     *
     * <p>入参是"期望的完整顺序"（imageId 列表），不是若干次两两交换。
     * 传完整顺序的好处：前端拖拽结束后直接把当前 DOM 顺序发过来即可，
     * 不会出现"交换了两张但服务端理解成别的意思"这种错位。
     */
    @Transactional
    public List<PoiImageVO> reorderPoiImages(String poiId, List<Long> orderedIds) {
        requirePoi(poiId);
        List<PoiImage> current = imageMapper.selectList(
                Wrappers.<PoiImage>lambdaQuery().eq(PoiImage::getPoiId, poiId));

        // 校验：传进来的 id 必须恰好是该景点的全部图片，不多不少。
        // 只做"部分重排"很容易把没提到的那些图排到不确定的位置上。
        if (orderedIds == null
                || orderedIds.size() != current.size()
                || !current.stream().map(PoiImage::getId).allMatch(orderedIds::contains)) {
            throw new BizException(ErrorCode.MEDIA_ORDER_INVALID);
        }

        Map<Long, Integer> pos = new HashMap<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            pos.put(orderedIds.get(i), i + 1);
        }
        for (PoiImage img : current) {
            PoiImage patch = new PoiImage();
            patch.setId(img.getId());
            patch.setSortOrder(pos.get(img.getId()));
            imageMapper.updateById(patch);
        }
        return listPoiImages(poiId);
    }

    // ============================================================
    // 读：轮播图
    // ============================================================

    /** 轮播图列表。onlyEnabled=true 时只返回启用的（游客端用） */
    public List<BannerVO> listBanners(boolean onlyEnabled) {
        var q = Wrappers.<SiteBanner>lambdaQuery();
        if (onlyEnabled) {
            q.eq(SiteBanner::getEnabled, 1);
        }
        q.orderByAsc(SiteBanner::getSortOrder).orderByAsc(SiteBanner::getId);
        return bannerMapper.selectList(q).stream().map(BannerVO::from).toList();
    }

    // ============================================================
    // 写：轮播图
    // ============================================================

    /** 新建一帧。图片可选 —— 先建好文案、回头再传图是常见的操作顺序 */
    @Transactional
    public BannerVO createBanner(MultipartFile file, SiteBanner fields) {
        SiteBanner e = new SiteBanner();
        e.setSortOrder(nextBannerSortOrder());
        e.setScene(fields.getScene() == null || fields.getScene().isBlank() ? "qinling" : fields.getScene());
        e.setEyebrow(fields.getEyebrow());
        e.setTitle(fields.getTitle() == null || fields.getTitle().isBlank() ? "未命名" : fields.getTitle());
        e.setSubtitle(fields.getSubtitle());
        e.setDescription(fields.getDescription());
        e.setLinkUrl(fields.getLinkUrl());
        e.setCta(fields.getCta());
        e.setEnabled(fields.getEnabled() == null ? 1 : fields.getEnabled());

        if (file != null && !file.isEmpty()) {
            e.setImagePath(storage.save(file, "banner"));
        }
        bannerMapper.insert(e);
        log.info("[M9] 新增轮播帧 id={} title={}", e.getId(), e.getTitle());
        return BannerVO.from(bannerMapper.selectById(e.getId()));
    }

    /** 改文案 / 启停 / 顺序。不动图片 —— 换图有单独接口，免得改文案时误传文件把图覆盖掉 */
    @Transactional
    public BannerVO updateBanner(Long id, SiteBanner fields) {
        requireBanner(id);

        SiteBanner patch = new SiteBanner();
        patch.setId(id);
        if (fields.getScene() != null && !fields.getScene().isBlank()) patch.setScene(fields.getScene());
        if (fields.getEyebrow() != null) patch.setEyebrow(fields.getEyebrow());
        if (fields.getTitle() != null && !fields.getTitle().isBlank()) patch.setTitle(fields.getTitle());
        if (fields.getSubtitle() != null) patch.setSubtitle(fields.getSubtitle());
        if (fields.getDescription() != null) patch.setDescription(fields.getDescription());
        if (fields.getLinkUrl() != null) patch.setLinkUrl(fields.getLinkUrl());
        if (fields.getCta() != null) patch.setCta(fields.getCta());
        if (fields.getEnabled() != null) patch.setEnabled(fields.getEnabled());
        bannerMapper.updateById(patch);

        return BannerVO.from(bannerMapper.selectById(id));
    }

    /** 替换某一帧的图片 */
    @Transactional
    public BannerVO replaceBannerImage(Long id, MultipartFile file) {
        SiteBanner old = requireBanner(id);
        String oldPath = old.getImagePath();

        String newPath = storage.save(file, "banner");
        SiteBanner patch = new SiteBanner();
        patch.setId(id);
        patch.setImagePath(newPath);
        bannerMapper.updateById(patch);

        storage.delete(oldPath);
        log.info("[M9] 替换轮播图 id={} {} -> {}", id, oldPath, newPath);
        return BannerVO.from(bannerMapper.selectById(id));
    }

    /** 清除某一帧的图片，回到手写 SVG 兜底画面 */
    @Transactional
    public BannerVO clearBannerImage(Long id) {
        SiteBanner old = requireBanner(id);
        SiteBanner patch = new SiteBanner();
        patch.setId(id);
        // 置空要用 setSql：MyBatis-Plus 的 updateById 默认忽略 null 字段，
        // 直接 setImagePath(null) 不会生成 SET image_path = NULL
        bannerMapper.update(null, Wrappers.<SiteBanner>lambdaUpdate()
                .eq(SiteBanner::getId, id)
                .setSql("image_path = NULL"));

        storage.delete(old.getImagePath());
        log.info("[M9] 清除轮播图 id={}", id);
        return BannerVO.from(bannerMapper.selectById(id));
    }

    @Transactional
    public void deleteBanner(Long id) {
        SiteBanner old = requireBanner(id);
        bannerMapper.deleteById(id);
        storage.delete(old.getImagePath());
        log.info("[M9] 删除轮播帧 id={} title={}", id, old.getTitle());
    }

    /** 重排轮播顺序。语义与 {@link #reorderPoiImages} 一致：传完整顺序 */
    @Transactional
    public List<BannerVO> reorderBanners(List<Long> orderedIds) {
        List<SiteBanner> all = bannerMapper.selectList(Wrappers.<SiteBanner>lambdaQuery());
        if (orderedIds == null
                || orderedIds.size() != all.size()
                || !all.stream().map(SiteBanner::getId).allMatch(orderedIds::contains)) {
            throw new BizException(ErrorCode.MEDIA_ORDER_INVALID);
        }
        Map<Long, Integer> pos = new HashMap<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            pos.put(orderedIds.get(i), i + 1);
        }
        for (SiteBanner b : all) {
            SiteBanner patch = new SiteBanner();
            patch.setId(b.getId());
            patch.setSortOrder(pos.get(b.getId()));
            bannerMapper.updateById(patch);
        }
        return listBanners(false);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private Poi requirePoi(String poiId) {
        if (poiId == null || poiId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST);
        }
        Poi p = poiMapper.selectById(poiId);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return p;
    }

    private PoiImage requireImage(Long id) {
        PoiImage e = id == null ? null : imageMapper.selectById(id);
        if (e == null) {
            throw new BizException(ErrorCode.MEDIA_NOT_FOUND);
        }
        return e;
    }

    private SiteBanner requireBanner(Long id) {
        SiteBanner e = id == null ? null : bannerMapper.selectById(id);
        if (e == null) {
            throw new BizException(ErrorCode.BANNER_NOT_FOUND);
        }
        return e;
    }

    /** 下一个排序号。取当前最大值 +1，而不是 count+1（删过图之后 count 会撞号） */
    private int nextSortOrder(String poiId) {
        List<PoiImage> list = imageMapper.selectList(
                Wrappers.<PoiImage>lambdaQuery().eq(PoiImage::getPoiId, poiId));
        return list.stream()
                .map(PoiImage::getSortOrder)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private int nextBannerSortOrder() {
        List<SiteBanner> list = bannerMapper.selectList(Wrappers.<SiteBanner>lambdaQuery());
        return list.stream()
                .map(SiteBanner::getSortOrder)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private void clearOtherCovers(String poiId, Long keepId) {
        List<PoiImage> others = new ArrayList<>(imageMapper.selectList(
                Wrappers.<PoiImage>lambdaQuery().eq(PoiImage::getPoiId, poiId)));
        for (PoiImage o : others) {
            if (o.getId().equals(keepId)) continue;
            if (o.getIsCover() != null && o.getIsCover() == 1) {
                PoiImage patch = new PoiImage();
                patch.setId(o.getId());
                patch.setIsCover(0);
                imageMapper.updateById(patch);
            }
        }
    }
}
