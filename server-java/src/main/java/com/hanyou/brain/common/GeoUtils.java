package com.hanyou.brain.common;

import com.hanyou.brain.entity.Poi;

/**
 * 地理距离计算。
 *
 * <p><b>为什么把它单独提出来：</b>这个公式有两个调用方 —— M1 的
 * {@code CityPackImporter} 用它生成 {@code poi_relation.distance_km}，
 * M5 的 {@code DiversionAdvisor} 用它给分流候选排序。两处必须**给出同一个数**：
 * 详情页说"距 12.3 km"、分流公告说"距 12.5 km"，运营第一反应就是数据错了。
 *
 * <p>抄一份的代价不是重复这几行，而是**两份会各自漂移**：一边改了半径、
 * 一边改了取整，差异要到界面上才看得出来。所以公式只留一份。
 *
 * <p>经纬度来自数据包，是估算值，保留到 0.01 km 已足够。
 */
public final class GeoUtils {

    /** 地球平均半径（km）。Haversine 用球面近似，对城市尺度的距离足够 */
    private static final double EARTH_R_KM = 6371.0;

    private GeoUtils() {
    }

    /**
     * Haversine 球面距离（公里）。
     *
     * @return 两点距离；任一坐标缺失时返回 {@link Double#MAX_VALUE}
     *         —— 不是 0，也不是抛异常。调用方都是"按距离排序后取最近的几个"，
     *         把缺失坐标的点排到最后（而不是排到最前）才是安全的行为。
     */
    public static double haversineKm(Poi a, Poi b) {
        if (a == null || b == null
                || a.getLat() == null || a.getLng() == null
                || b.getLat() == null || b.getLng() == null) {
            return Double.MAX_VALUE;
        }
        double lat1 = a.getLat().doubleValue();
        double lng1 = a.getLng().doubleValue();
        double lat2 = b.getLat().doubleValue();
        double lng2 = b.getLng().doubleValue();

        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_R_KM * Math.asin(Math.sqrt(s));
    }
}
