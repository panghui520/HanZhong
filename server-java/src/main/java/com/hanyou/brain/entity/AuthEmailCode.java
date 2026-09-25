package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 邮箱验证码（M8）。
 *
 * <p>五道闸全部在 AuthCodeService 里判定，前端不参与：
 * 有效期、发送冷却、错误次数上限、一次性使用、同时只有一条有效码。
 *
 * <p>codeHash 不存明文。这里用固定盐的 SHA-256 而非 BCrypt 是刻意的：
 * BCrypt 每次加盐结果都不同，就无法用 code_hash 做等值查询。
 * 6 位数字本就不是抗离线爆破的强度，真正的防线是"5 分钟 + 5 次错误上限"。
 */
@Data
@TableName("auth_email_code")
public class AuthEmailCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目标邮箱，落库前转小写 */
    private String email;

    /** SHA-256(邮箱 + 验证码 + 固定盐) 的小写十六进制 */
    private String codeHash;

    /** REGISTER / RESET */
    private String purpose;

    /** 已错误的校验次数 */
    private Integer attemptCount;

    /** 错误次数上限，超过即作废 */
    private Integer maxAttempts;

    private LocalDateTime expiresAt;

    /** 非 NULL 表示已用过（一次性） */
    private LocalDateTime usedAt;

    /** 主动作废时间：发新码时作废旧码、错误超限时作废 */
    private LocalDateTime invalidatedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
