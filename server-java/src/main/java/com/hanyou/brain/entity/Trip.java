package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次旅行（M4 智能行程）。
 *
 * <p>这是"谁的哪一次出行"的身份，内容基本不变。会随对话变的字段
 * （已选酒店、预订状态）全部在 {@link TripContext} 里，一次旅行一行。
 *
 * <p>本表**刻意没有 cityCode**：CityPackImporter 每次启动按 city_code
 * 全量删除重灌 6 张业务表，而行程是运行期数据，不能因为重启一次服务就没了。
 * 目的地城市用 {@link #destinationCode} 表达 —— 语义也更准，
 * 它是"这次去哪"，不是"这条数据属于哪个城市包"。
 *
 * <p>{@link #code} 形如 {@code 2026-hanzhong-001}。对外用业务编码而不是自增 id：
 * id 会被写进前端 URL 和演示截图，{@code 1} 看起来像测试数据，
 * 而 {@code 2026-hanzhong-001} 一眼能读出"谁、去哪、第几次"。
 */
@Data
@TableName("trip")
public class Trip {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户，-> app_user.id */
    private Long userId;

    /** 业务编码，形如 2026-hanzhong-001。(user_id, code) 唯一 */
    private String code;

    /** 目的地城市编码，与 citypack 目录名一致（hanzhong） */
    private String destinationCode;

    /** 目的地显示名（汉中）。写进提示词的是这个，不是编码 */
    private String destination;

    /** ACTIVE 进行中 / ARCHIVED 已归档。阶段二只产生 ACTIVE */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
