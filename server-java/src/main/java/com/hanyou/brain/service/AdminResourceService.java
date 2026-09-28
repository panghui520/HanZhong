package com.hanyou.brain.service;

import java.util.List;
import java.util.Map;

import com.hanyou.brain.vo.AdminPoiVO;
import com.hanyou.brain.vo.AdminProductVO;

/**
 * 资源管理（M10，运营端）。
 *
 * <p><b>这一层刻意不新建任何资源表。</b>三类资源各自落在已有的表上：
 * <ul>
 *   <li>景点（含乡村旅游）→ {@code poi}，{@code business_type in (SCENIC, RURAL_SPOT)}</li>
 *   <li>美食 → {@code poi}，{@code business_type = FOOD}</li>
 *   <li>农产品 → {@code product}</li>
 * </ul>
 * "景点"与"美食"在库里本来就是同一张表的不同业态 —— 这是 M1 多业态统一模型的
 * 设计，管理端顺着它分两个入口即可，不该为了管理方便把它拆成两张表。
 *
 * <p><b>上架 / 下架优先于删除。</b>删除是硬保护：被订单、足迹、体验、配图或
 * 资源关系引用的资源一律拒绝删除，并告诉运营"改用下架"。理由见
 * {@code ErrorCode.RESOURCE_IN_USE} 与 {@code RESOURCE_PACK_LOCKED}。
 *
 * <p><b>写入的资源一律标 {@code source=ADMIN}。</b>CityPackImporter 每次启动
 * 会按城市全量重建 {@code source='PACK'} 的行，ADMIN 的行不在重建范围内 ——
 * 这是"运营加一个景点，重启一次就没了"这个问题的解法。详见
 * {@code db/V11__m10_resource_admin.sql}。
 */
public interface AdminResourceService {

    /** 资源类型：景点（含乡村旅游） */
    String TYPE_SCENIC = "scenic";

    /** 资源类型：美食 */
    String TYPE_FOOD = "food";

    /** 资源类型：农产品 */
    String TYPE_PRODUCT = "product";

    // ============================================================
    // 景点 / 美食（poi）
    // ============================================================

    /**
     * 列表。
     *
     * @param type    资源类型，只接受 {@link #TYPE_SCENIC} 或 {@link #TYPE_FOOD}
     * @param keyword 关键词，匹配名称/简介/标签/区县/编码；null 表示不过滤
     * @param status  1 只看上架 / 0 只看下架 / null 全部
     */
    List<AdminPoiVO> listPois(String type, String keyword, Integer status);

    /** 新增。{@code id} 由服务端生成（P-ADM-xxx），不接受客户端指定 */
    AdminPoiVO createPoi(String type, Map<String, Object> body);

    /** 编辑。只改请求体里出现的字段 —— 没传的保持原值，不会被清空 */
    AdminPoiVO updatePoi(String type, String id, Map<String, Object> body);

    /** 上架 / 下架。{@code status} 只接受 0 或 1 */
    AdminPoiVO updatePoiStatus(String type, String id, Integer status);

    /** 删除。数据包带来的、以及被任何业务数据引用的，都会拒绝 */
    void deletePoi(String type, String id);

    // ============================================================
    // 农产品（product）
    // ============================================================

    /** 列表。参数语义同 {@link #listPois} */
    List<AdminProductVO> listProducts(String keyword, Integer status);

    AdminProductVO createProduct(Map<String, Object> body);

    AdminProductVO updateProduct(String id, Map<String, Object> body);

    AdminProductVO updateProductStatus(String id, Integer status);

    void deleteProduct(String id);
}
