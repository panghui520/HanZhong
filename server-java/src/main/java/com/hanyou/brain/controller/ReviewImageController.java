package com.hanyou.brain.controller;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hanyou.brain.auth.AuthUser;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.media.MediaStorageService;

import lombok.RequiredArgsConstructor;

/**
 * 评价图片上传（M6）。
 *
 * <p><b>为什么不复用 M9 的运营上传接口：</b>两者权限不同。运营上传挂在
 * {@code /api/admin/media/**} 下，由 SecurityConfig 统一要求 OPERATOR 角色；
 * 评价图片则是任何登录用户都要能传的。想复用就得把那个接口从 ADMIN_PREFIX
 * 里摘出来 —— 那等于把一个原本只有运营能调的接口放开给所有人，
 * 风险比多写一个受控接口大得多。
 *
 * <p>存储能力直接复用 {@link MediaStorageService}（落磁盘、库里只存相对路径、
 * 大小与格式校验都在那里），不重新实现一遍。
 *
 * <p><b>一次只传一张，由前端循环调用。</b>不做多文件上传的理由：
 * 三张图分三次请求，任何一张失败都只影响那一张，用户可以单独重试；
 * 一次传三张时失败一张就得整批重来。评价最多 3 张，请求数不是问题。
 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewImageController {

    /** 评价图片放这个子目录，与 poi/、banner/ 平级，便于按用途清理 */
    private static final String SUB_DIR = "review";

    private final MediaStorageService storage;

    /**
     * 上传一张评价图片，返回它的相对路径。
     *
     * <p>返回**相对路径**而不是完整 URL：与 poi_image 存的东西保持一致，
     * 前端统一用同一个函数把它拼成 {@code /api/media/xxx}。
     * 返回完整 URL 的话，将来换域名或加 CDN 就得去改库里的历史数据。
     */
    @PostMapping("/images")
    public Result<Map<String, String>> upload(@AuthenticationPrincipal AuthUser me,
                                              @RequestParam("file") MultipartFile file) {
        // 兜底守卫：这个接口在 SecurityConfig 里配了 authenticated，
        // 但万一以后被误放进白名单，这里也不能让 null 用户往磁盘上写文件
        if (me == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // 按用户分目录：既避免同名冲突，也让"某个用户传了什么"一目了然，
        // 将来清理能整目录删。userId 是纯数字，不会破坏 SAFE_SEGMENT 校验。
        String path = storage.save(file, SUB_DIR + "/" + me.userId());
        return Result.ok(Map.of("path", path));
    }
}
