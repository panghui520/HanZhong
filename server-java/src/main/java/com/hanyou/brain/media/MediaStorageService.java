package com.hanyou.brain.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.config.HanYouProperties;

/**
 * 媒体文件存储（M9）。
 *
 * <p>只做三件事：把上传的文件安全地落到磁盘、按相对路径删除、把根目录暴露给静态资源映射。
 * 业务规则（谁有几张图、哪张是封面、顺序怎么排）在 {@link MediaService}，不在这里。
 *
 * <p><b>三条安全约束，缺一不可：</b>
 *
 * <p>1. <b>文件名由服务端生成</b>，绝不使用上传上来的原始文件名。
 * 原始名里可能带 {@code ../}、空字节、超长 Unicode，或者两个不同用户传同名文件互相覆盖。
 * 这里一律换成 UUID。
 *
 * <p>2. <b>子目录名做白名单校验</b>。子目录来自 URL 里的 poiId，
 * 虽然正常都是 {@code P-SCE-001} 这种，但请求是外部可控的 ——
 * 不校验的话 {@code poi_id=../../} 就能把文件写到媒体目录之外去。
 *
 * <p>3. <b>删除前再做一次根目录包含校验</b>。路径存在数据库里，理论上可信，
 * 但多一道 {@code normalize + startsWith} 的成本几乎为零，而它能挡住
 * "将来某次改动让非法路径进了库"这一类回归。
 */
@Service
public class MediaStorageService {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageService.class);

    /**
     * 对外访问前缀。WebConfig 注册静态资源映射用的是同一个值 ——
     * 改这里就等于同时改了"文件放在哪个 URL 下"，不需要两处同步。
     */
    public static final String URL_PREFIX = "/api/media/";

    /** 单文件上限。景区大图压到 5MB 以内足够清晰，再大就该先压缩 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 允许的 MIME 与对应扩展名。只认图片，不接受 PDF/SVG 等可执行内容 */
    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    /** 路径段白名单：字母、数字、下划线、连字符、点。点是为了文件名里的扩展名 */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    private final Path root;

    public MediaStorageService(HanYouProperties props) {
        // 解析成绝对路径并归一化：后面所有包含性校验都基于它
        this.root = Paths.get(props.getMediaDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // 建不出来就意味着上传功能整个不可用，属于启动期配置错误，早失败比晚失败好
            throw new IllegalStateException("无法创建媒体目录: " + root, e);
        }
        log.info("[M9] 媒体目录 = {}", root);
    }

    /** 媒体根目录（绝对路径）。WebConfig 用它注册静态资源映射 */
    public Path root() {
        return root;
    }

    /**
     * 保存上传的图片。
     *
     * @param file      上传的文件
     * @param subDir    子目录，如 {@code poi/P-SCE-001} 或 {@code banner}
     * @return 相对媒体根目录的路径（正斜杠），存进数据库的就是它
     */
    public String save(MultipartFile file, String subDir) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.MEDIA_EMPTY);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.MEDIA_TOO_LARGE);
        }

        String ext = extensionOf(file);
        Path dir = resolveInsideRoot(subDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[M9] 图片保存失败 subDir={}", subDir, e);
            throw new BizException(ErrorCode.MEDIA_SAVE_FAILED);
        }

        // 统一用正斜杠：Windows 上 Path.toString() 会给反斜杠，
        // 存进库后再拼成 URL 会变成 /api/media/poi\xxx.jpg，前端请求直接 404
        return (subDir + "/" + filename).replace('\\', '/');
    }

    /**
     * 删除文件。文件不存在视为成功——调用方关心的是"删完之后它不在了"，
     * 而不是"它到底存在过没有"。删不掉（被占用等）只记日志不抛异常：
     * 数据库里的记录已经删了，为了一个孤儿文件让整个删除操作失败不合理。
     */
    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return;
        try {
            Path target = resolveInsideRoot(relativePath);
            boolean gone = Files.deleteIfExists(target);
            if (!gone) {
                log.debug("[M9] 待删除的文件本就不存在: {}", relativePath);
            }
        } catch (BizException e) {
            // 路径越界：库里存了非法路径。记下来但不删，也绝不按它去删别的东西
            log.warn("[M9] 拒绝删除越界路径: {}", relativePath);
        } catch (IOException e) {
            log.warn("[M9] 文件删除失败（已忽略，数据库记录照常删除）: {}", relativePath, e);
        }
    }

    /**
     * 把相对路径解析成根目录内的绝对路径，越界即抛异常。
     *
     * <p>这是所有文件操作的唯一入口 —— 让校验只有一处，比在每个调用点各写一遍可靠。
     */
    private Path resolveInsideRoot(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new BizException(ErrorCode.MEDIA_NOT_FOUND);
        }
        // 统一成正斜杠再切段：Windows 上传来的路径可能带反斜杠
        String normalized = relative.replace('\\', '/');
        for (String seg : normalized.split("/")) {
            if (seg.isEmpty()) continue;
            // "." 与 ".." 即使能通过字符白名单也必须单独拦掉，它们是穿越的载体
            if (".".equals(seg) || "..".equals(seg) || !SAFE_SEGMENT.matcher(seg).matches()) {
                throw new BizException(ErrorCode.MEDIA_NOT_FOUND);
            }
        }
        // 最后一道：解析后必须仍在根目录之内。前面逐段校验已能挡住绝大多数情况，
        // 这一步是兜底——成本近乎为零，却能挡住"将来某次改动放进来非法路径"的回归
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new BizException(ErrorCode.MEDIA_NOT_FOUND);
        }
        return resolved;
    }

    /** 从 MIME 推断扩展名。不信任原始文件名里的扩展名——那是上传方说了算的 */
    private String extensionOf(MultipartFile file) {
        String contentType = file.getContentType();
        String ext = contentType == null ? null : ALLOWED.get(contentType.toLowerCase(Locale.ROOT));
        if (ext == null) {
            throw new BizException(ErrorCode.MEDIA_TYPE_UNSUPPORTED);
        }
        return ext;
    }
}
