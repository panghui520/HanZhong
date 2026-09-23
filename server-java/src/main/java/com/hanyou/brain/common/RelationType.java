package com.hanyou.brain.common;

/**
 * 资源关系类型。
 * 取值与 db/V1__m1_resource.sql 中 poi_relation.relation_type 的注释一一对应，
 * 导入器写入、详情页读取都引用这里的常量，避免两边各写一遍字符串字面量。
 */
public final class RelationType {

    /** 邻近：同城、距离近，用于串联同一线路 */
    public static final String NEARBY = "NEARBY";

    /** 配套：吃住行等不同业态，用于构成一条可执行的行程 */
    public static final String SUPPORT = "SUPPORT";

    /** 同片区乡村：乡村点之间的联动 */
    public static final String SAME_VILLAGE = "SAME_VILLAGE";

    /** 可分流承接：景区 -> 乡村，是"客流下乡"在数据层的关系载体 */
    public static final String DIVERSION = "DIVERSION";

    private RelationType() {
    }
}
