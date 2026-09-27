package com.hanyou.brain.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.Trip;
import com.hanyou.brain.entity.TripContext;
import com.hanyou.brain.mapper.TripContextMapper;
import com.hanyou.brain.mapper.TripMapper;
import com.hanyou.brain.vo.CityMetaVO;
import com.hanyou.brain.vo.SelectedHotelVO;
import com.hanyou.brain.vo.TripContextVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M4 智能行程：旅行 + 旅行上下文。
 *
 * <p><b>这个类解决的是"助手记不住"的问题。</b>阶段一的助手每次提问都是
 * 独立的一问一答：用户上一轮在卡片上点了"选择酒店"，下一轮问"这附近
 * 有什么好吃的"，助手只能反问"您住哪家酒店"。把用户的当前行程落库之后，
 * 服务端就能在每一轮对话里把"目的地 + 已选酒店 + 坐标"喂给模型与工具，
 * 于是"附近"这个词终于有了确定的指代。
 *
 * <p><b>为什么是"懒创建"而不是登录时创建：</b>
 * 登录/注册是 M8 已经验收过的链路，往里面塞一次 M4 的写库，会让
 * "登录失败"多出一种与认证无关的原因；而且一个从不打开助手的用户
 * 也不需要有一条行程。所以行程在**第一次访问 /api/trips/current 时**产生。
 *
 * <p>代价是 GET 带了写副作用。这一点是清楚的、也是被接受的：
 * 它符合"取我的当前资源，没有就给我建一个"的常见语义，
 * 而真正需要提防的是它被并发调用 —— 所以 {@code trip} 上有
 * {@code uk_user_code} 唯一键兜底，见 {@link #ensureTrip}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 未预订。本阶段不接真实预订，所以只会是这一个值 */
    private static final String BOOKING_NOT_BOOKED = "not_booked";

    /** 数据来源。当前只有高德；留成常量是为了以后接自有酒店数据时改一处 */
    private static final String SOURCE_AMAP = "amap";

    /*
     * 中国大陆的经纬度范围（含港澳台）。用来拦住两类真实发生过的错误：
     *   ① 经纬度传反了 —— 高德坐标是"经度,纬度"，而大多数人习惯先写纬度。
     *      传反时 lat 会变成 107，直接越界，这里能立刻拦下。
     *   ② 传了别的东西 —— 比如把 "107.02,33.07" 整个字符串塞进 longitude。
     * 高德只覆盖国内，所以这个范围不会误伤正常数据。
     */
    private static final double LNG_MIN = 73.0;
    private static final double LNG_MAX = 136.0;
    private static final double LAT_MIN = 3.0;
    private static final double LAT_MAX = 54.0;

    private final TripMapper tripMapper;
    private final TripContextMapper tripContextMapper;
    private final PoiService poiService;

    // ------------------------------------------------------------------
    // 对外：当前行程
    // ------------------------------------------------------------------

    /**
     * 取当前行程与它的上下文。没有就建一个（懒创建，见类注释）。
     *
     * <p>一次返回"行程身份 + 已选酒店"，而不是拆成两个接口：前端拿它做
     * 两件事 —— 顶部显示当前行程、高亮已选中的酒店卡片。这两件事总是
     * 同时发生，拆开只会多一次请求，以及"两个接口返回的不是同一次行程"
     * 这类只在极端时序下出现的 bug。
     */
    @Transactional
    public TripContextVO current(Long userId) {
        Trip trip = ensureTrip(userId);
        return toVO(trip, ensureContext(trip.getId()));
    }

    /**
     * 当前行程 id，没有就建一个（懒创建）。
     *
     * <p>给 M6 的足迹用：一次打卡要挂在"这次出行"上，否则足迹无法回答
     * "哪几次到访属于同一次旅行"。与 {@link #current} 同一套懒创建逻辑，
     * 不复制一遍 {@code ensureTrip} 的并发处理。
     *
     * <p>返回的是 id 而不是整个 VO：足迹那边只写一个外键列，
     * 拿整个 VO 会顺带把 trip_context 也读一遍 —— 那是白读。
     */
    @Transactional
    public Long ensureCurrentTripId(Long userId) {
        return ensureTrip(userId).getId();
    }

    /**
     * 把某家酒店记为本次行程的住处。
     *
     * <p>提交的字段来自前端刚收到的那张高德卡片。这里**不做"这条 POI
     * 是否真实存在"的校验** —— 那需要一次 place/detail 调用，而高德客户端
     * 目前只在 Python 侧（{@code server-ai/app/amap.py}），Java 侧没有。
     * 本阶段接受客户端提交是合理的：上下文只影响用户自己的下一次提问，
     * 没有任何金额与权限后果。等阶段五真的接预订（那时数据正确性开始
     * 涉及钱），再把这一步换成服务端按 poi_id 反查高德。
     *
     * <p>但"结构合法 + 坐标在国内范围内"必须校验，因为它拦的是
     * 经纬度传反这类**会静默搜到错误结果**的错误，见 {@link #LNG_MIN}。
     */
    @Transactional
    public TripContextVO selectHotel(Long userId, Map<String, Object> body) {
        Trip trip = ensureTrip(userId);
        TripContext ctx = ensureContext(trip.getId());

        String poiId = trimToEmpty(BodyReader.str(body, "poi_id"));
        String name = trimToEmpty(BodyReader.str(body, "name"));
        if (poiId.isEmpty() || name.isEmpty()) {
            throw new BizException(ErrorCode.HOTEL_INFO_INCOMPLETE);
        }

        BigDecimal lng = BodyReader.decimal(body, "longitude");
        BigDecimal lat = BodyReader.decimal(body, "latitude");
        if (!inChina(lng, lat)) {
            throw new BizException(ErrorCode.HOTEL_COORD_INVALID);
        }

        String address = trimToEmpty(BodyReader.str(body, "address"));
        String source = trimToEmpty(BodyReader.str(body, "source"));

        // 换了另一家酒店 -> 预订状态归零。
        // "已预订"这件事是绑在**某一家**酒店上的；用户从 A 改选 B 之后，
        // 还留着"已预订"就是在骗下一轮对话（助手会说"您已预订，不必再订"）。
        // 重复选择同一家则不动状态，否则点两次卡片就把已预订擦掉了。
        boolean switched = !poiId.equals(ctx.getSelectedHotelPoiId());
        if (switched) {
            ctx.setHotelBookingStatus(BOOKING_NOT_BOOKED);
        }

        writeHotel(ctx, poiId, name, address, lng, lat,
                source.isEmpty() ? SOURCE_AMAP : source);

        log.info("[M4] 用户 {} 的行程 {} 选定酒店：{}（{}）",
                userId, trip.getCode(), name, poiId);
        return toVO(trip, ctx);
    }

    /**
     * 取消已选酒店。
     *
     * <p>刻意**不删 trip_context 行**，只把酒店那几列清空：行还在，
     * "这次旅行"就还在（阶段五要往同一行里写行程、偏好）。删行的话，
     * 下一次提问又会走一遍懒创建，行程编号也跟着变 —— 用户只是
     * 换了主意，不该看起来像换了一次旅行。
     */
    @Transactional
    public TripContextVO clearHotel(Long userId) {
        Trip trip = ensureTrip(userId);
        TripContext ctx = ensureContext(trip.getId());

        writeHotel(ctx, null, null, null, null, null, null);
        ctx.setHotelBookingStatus(BOOKING_NOT_BOOKED);
        tripContextMapper.update(null, Wrappers.<TripContext>lambdaUpdate()
                .eq(TripContext::getId, ctx.getId())
                .set(TripContext::getHotelBookingStatus, BOOKING_NOT_BOOKED));

        log.info("[M4] 用户 {} 的行程 {} 取消了已选酒店", userId, trip.getCode());
        return toVO(trip, ctx);
    }

    // ------------------------------------------------------------------
    // 对外：给 AI 链路的上下文摘要
    // ------------------------------------------------------------------

    /**
     * 组装喂给 AI 服务的上下文（M4 阶段二的核心产出）。
     *
     * <p>只放"这一轮对话真的会用到"的字段，键名直接是 snake_case ——
     * 它要被序列化后放进 {@code POST /ai/agent} 的请求体，Python 侧按
     * 这些键取值。这里**不包 VO 对象**：VO 是给前端看的形状，
     * 里面带着 tripId、status 这些模型与工具都用不上的东西，
     * 混进提示词只会稀释真正有用的那两行。
     *
     * <p><b>没有行程时返回空 Map，且不创建任何行。</b>
     * 这个方法是只读的：它由 {@code POST /api/ai/agent} 调用，而该端点
     * 是 permitAll 的，未登录用户也会走到这里。给一次提问顺手建一条行程，
     * 会让"读接口"变成"写接口"，而调用方完全看不出这一点。
     *
     * @return 上下文；无行程或无上下文时为空 Map（调用方按"没有上下文"处理）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> aiContext(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        Trip trip = findActive(userId);
        if (trip == null) {
            return Map.of();
        }
        TripContext ctx = findContext(trip.getId());
        if (ctx == null) {
            return Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("destination", trip.getDestination());

        String location = hotelLocation(ctx);
        if (!location.isEmpty()) {
            Map<String, Object> hotel = new LinkedHashMap<>();
            hotel.put("poi_id", ctx.getSelectedHotelPoiId());
            hotel.put("name", ctx.getSelectedHotelName());
            hotel.put("address", ctx.getSelectedHotelAddress());
            // 拼好的"经度,纬度"，与高德 location 参数格式一致。
            // 让 Python 侧直接拿去用，而不是自己从两列里拼 —— 拼反了
            // 会搜到别的地方，而且返回结果看起来完全正常。
            hotel.put("location", location);
            hotel.put("source", ctx.getSelectedHotelSource());
            out.put("selected_hotel", hotel);
            out.put("hotel_booking_status", ctx.getHotelBookingStatus());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 内部：懒创建
    // ------------------------------------------------------------------

    /** 当前行程 = 该用户最近一条 ACTIVE 的行程。阶段二每个用户只有一条 */
    private Trip findActive(Long userId) {
        List<Trip> found = tripMapper.selectList(Wrappers.<Trip>lambdaQuery()
                .eq(Trip::getUserId, userId)
                .eq(Trip::getStatus, STATUS_ACTIVE)
                .orderByDesc(Trip::getId)
                .last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    private TripContext findContext(Long tripId) {
        return tripContextMapper.selectOne(
                Wrappers.<TripContext>lambdaQuery().eq(TripContext::getTripId, tripId));
    }

    /**
     * 取当前行程，没有就建一条。
     *
     * <p>并发安全靠数据库而不是靠代码：两个请求同时到达时，它们算出的
     * 编码都是 {@code 2026-hanzhong-001}，{@code uk_user_code} 唯一键
     * 会让后到的那个插入失败。这里捕获这个异常并**重新读一次** ——
     * 赢家已经把行建好了，我们只需要拿到它。这不是"忽略错误"，
     * 而是把"谁先谁后"这个本就无所谓的问题交给数据库裁决。
     *
     * <p>捕获之后本事务仍可继续：MySQL 的主键/唯一键冲突只回滚**当前语句**，
     * 不像某些数据库会作废整个事务。
     */
    private Trip ensureTrip(Long userId) {
        Trip existing = findActive(userId);
        if (existing != null) {
            return existing;
        }

        CityMetaVO meta = poiService.getCityMeta();
        Trip fresh = new Trip();
        fresh.setUserId(userId);
        fresh.setCode(nextCode(userId, meta.getCityCode()));
        fresh.setDestinationCode(meta.getCityCode());
        fresh.setDestination(meta.getName());
        fresh.setStatus(STATUS_ACTIVE);
        try {
            tripMapper.insert(fresh);
            log.info("[M4] 为用户 {} 创建行程 {}", userId, fresh.getCode());
            return fresh;
        } catch (DuplicateKeyException e) {
            Trip winner = findActive(userId);
            if (winner == null) {
                throw e;
            }
            return winner;
        }
    }

    /**
     * 生成业务编码：年份 + 目的地编码 + 该用户当年的序号，形如 {@code 2026-hanzhong-001}。
     *
     * <p>序号按"该用户今年去该城市的第几次"算。用 3 位补零是为了让编号
     * 定长、在列表里对齐，不是为了支持 999 次。
     */
    private String nextCode(Long userId, String cityCode) {
        String prefix = LocalDate.now().getYear() + "-" + cityCode + "-";
        long used = tripMapper.selectCount(Wrappers.<Trip>lambdaQuery()
                .eq(Trip::getUserId, userId)
                .likeRight(Trip::getCode, prefix));
        return prefix + String.format("%03d", used + 1);
    }

    /** 一次旅行一行。并发下同样靠 uk_trip 兜底，处理方式与 {@link #ensureTrip} 相同 */
    private TripContext ensureContext(Long tripId) {
        TripContext existing = findContext(tripId);
        if (existing != null) {
            return existing;
        }
        TripContext fresh = new TripContext();
        fresh.setTripId(tripId);
        fresh.setHotelBookingStatus(BOOKING_NOT_BOOKED);
        try {
            tripContextMapper.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            TripContext winner = findContext(tripId);
            if (winner == null) {
                throw e;
            }
            return winner;
        }
    }

    // ------------------------------------------------------------------
    // 内部：写酒店
    // ------------------------------------------------------------------

    /**
     * 写酒店那 6 列。传 null 即清空。
     *
     * <p><b>为什么不用 {@code updateById}：</b>MyBatis-Plus 的实体更新默认
     * 跳过 null 字段（FieldStrategy.NOT_NULL），于是"清空已选酒店"会变成
     * 一次什么都没改的空更新，接口返回 200 而数据还在 —— 这类"看起来成功了
     * 但没生效"的问题最难查。用 UpdateWrapper 显式 set 每一列，
     * null 就会被真正写成 NULL。
     *
     * <p>顺带的好处是"哪些列属于酒店"在这里只出现一次，
     * 以后加列不会漏掉某一条写路径。
     */
    private void writeHotel(TripContext ctx, String poiId, String name, String address,
                            BigDecimal lng, BigDecimal lat, String source) {
        tripContextMapper.update(null, Wrappers.<TripContext>lambdaUpdate()
                .eq(TripContext::getId, ctx.getId())
                .set(TripContext::getSelectedHotelPoiId, poiId)
                .set(TripContext::getSelectedHotelName, name)
                .set(TripContext::getSelectedHotelAddress, address)
                .set(TripContext::getSelectedHotelLng, lng)
                .set(TripContext::getSelectedHotelLat, lat)
                .set(TripContext::getSelectedHotelSource, source));

        // 回填内存对象，让调用方不必为了组装返回值再查一次库
        ctx.setSelectedHotelPoiId(poiId);
        ctx.setSelectedHotelName(name);
        ctx.setSelectedHotelAddress(address);
        ctx.setSelectedHotelLng(lng);
        ctx.setSelectedHotelLat(lat);
        ctx.setSelectedHotelSource(source);
    }

    // ------------------------------------------------------------------
    // 内部：转换与校验
    // ------------------------------------------------------------------

    private TripContextVO toVO(Trip trip, TripContext ctx) {
        TripContextVO vo = new TripContextVO();
        vo.setTripId(trip.getId());
        vo.setTripCode(trip.getCode());
        vo.setDestination(trip.getDestination());
        vo.setDestinationCode(trip.getDestinationCode());
        vo.setStatus(trip.getStatus());
        vo.setHotelBookingStatus(ctx.getHotelBookingStatus());
        vo.setHotel(toHotelVO(ctx));
        return vo;
    }

    /** 没有已选酒店时返回 null（Jackson 配了 non_null，字段会整个消失） */
    private static SelectedHotelVO toHotelVO(TripContext ctx) {
        if (!StringUtils.hasText(ctx.getSelectedHotelPoiId())) {
            return null;
        }
        SelectedHotelVO h = new SelectedHotelVO();
        h.setPoiId(ctx.getSelectedHotelPoiId());
        h.setName(ctx.getSelectedHotelName());
        h.setAddress(ctx.getSelectedHotelAddress());
        String lng = coord(ctx.getSelectedHotelLng());
        String lat = coord(ctx.getSelectedHotelLat());
        h.setLongitude(lng);
        h.setLatitude(lat);
        h.setLocation(lng.isEmpty() || lat.isEmpty() ? null : lng + "," + lat);
        h.setSource(ctx.getSelectedHotelSource());
        return h;
    }

    /** 拼好的"经度,纬度"；没有坐标时返回空串 */
    private static String hotelLocation(TripContext ctx) {
        String lng = coord(ctx.getSelectedHotelLng());
        String lat = coord(ctx.getSelectedHotelLat());
        return lng.isEmpty() || lat.isEmpty() ? "" : lng + "," + lat;
    }

    /**
     * DECIMAL(10,6) 读出来是 {@code 107.020000}，去掉无意义的尾零给高德。
     *
     * <p>{@code toPlainString} 而不是 {@code toString}：后者在特定值上会
     * 输出科学计数法（{@code 1.07E+2}），那是个非法坐标。
     */
    private static String coord(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static boolean inChina(BigDecimal lng, BigDecimal lat) {
        if (lng == null || lat == null) {
            return false;
        }
        double x = lng.doubleValue();
        double y = lat.doubleValue();
        return x >= LNG_MIN && x <= LNG_MAX && y >= LAT_MIN && y <= LAT_MAX;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
