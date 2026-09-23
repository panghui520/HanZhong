package com.hanyou.brain.service;

import java.util.List;

import com.hanyou.brain.vo.ProductCategoryVO;
import com.hanyou.brain.vo.ProductVO;

/** M2 乡村农产品。产品必须挂靠产地或体验锚点，所以没有"全部商品"式的独立入口 */
public interface ProductService {

    /**
     * 产品列表。
     *
     * <p>四个参数可任意组合，都为 null 时返回本市全部在售产品。
     * 详情页用 poiId、体验页用 experienceId、M4 规划用 categoryCode、
     * M7 运营检索用 keyword——同一个列表接口，不按调用方拆成三条路径。
     *
     * @param poiId         产地乡村点
     * @param experienceId  体验锚点
     * @param categoryCode  分类编码（不是分类中文名，中文名会被改）
     * @param keyword       在名称 / 溯源文案 / 标签 / 产地村里模糊匹配
     */
    List<ProductVO> listProducts(String poiId, String experienceId, String categoryCode, String keyword);

    /** 产品详情。不存在时抛 1001 */
    ProductVO getProduct(String id);

    /** 分类列表，含各分类在售产品数。首页筛选用 */
    List<ProductCategoryVO> listCategories();
}
