package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 用户账号（M8 认证与权限）。
 *
 * <p>本表**刻意没有 cityCode**：CityPackImporter 每次启动按 city_code 全量删除重灌
 * 业务表，用户数据只要不挂 city_code 就永远不会被导入器碰到。
 *
 * <p>passwordHash 可空是有意的。注册分两步：先验证邮箱（此时用户行已建出，
 * 状态 PENDING、没有密码），再设置密码转 ACTIVE。不这么做就得把"已验证邮箱"
 * 这个中间状态存到临时表里再搬运，多一处可能不一致的地方。
 */
@Data
@TableName("app_user")
public class AppUser {

    /** 主键。注意不能用 IdType.INPUT——那是给城市数据包的字符串编码用的，这里要自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录邮箱，落库前一律转小写 */
    private String email;

    /** BCrypt 哈希（60 字符），NULL 表示尚未设置密码 */
    private String passwordHash;

    private String nickname;

    /** GUEST / OPERATOR。注册接口只写 GUEST，不接受前端传值 */
    private String role;

    /** PENDING 待邮箱验证 / ACTIVE 正常 / DISABLED 已禁用 */
    private String status;

    /** 邮箱是否已验证：0 否 / 1 是 */
    private Integer emailVerified;

    /**
     * JWT 版本号。签发令牌时写进 claims，每次校验都比对一次。
     * 递增它 => 该用户所有已签发令牌立即失效（退出登录 / 强制下线）。
     */
    private Integer tokenVersion;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
