-- ============================================================
-- M1 统一资源（POI）
-- 只建本模块需要的三张表。后续模块各自新增 V2__ / V3__ 文件，
-- 一个模块一次纵切，不预先建后面用不到的表。
-- 执行：mysql -uroot -p < db/V1__m1_resource.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS hanyou_brain
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE hanyou_brain;

-- ------------------------------------------------------------
-- 城市档案：对应 citypack/<city>/meta.json
-- ------------------------------------------------------------
DROP TABLE IF EXISTS city_profile;
CREATE TABLE city_profile (
  city_code     VARCHAR(32)  NOT NULL COMMENT '城市编码，如 hanzhong',
  name          VARCHAR(64)  NOT NULL COMMENT '城市名',
  province      VARCHAR(32)           COMMENT '所属省份',
  center_lng    DECIMAL(10,6)         COMMENT '中心点经度（估算）',
  center_lat    DECIMAL(10,6)         COMMENT '中心点纬度（估算）',
  tagline       VARCHAR(128)          COMMENT '一句话标签',
  summary       TEXT                  COMMENT '城市简介',
  data_origin   VARCHAR(16)  DEFAULT 'PUBLIC' COMMENT 'PUBLIC / SYNTHETIC',
  disclaimer    TEXT                  COMMENT '数据来源与仿真声明',
  version       VARCHAR(16)           COMMENT '数据包版本',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (city_code)
) ENGINE=InnoDB COMMENT='城市档案（City Pack 元数据）';

-- ------------------------------------------------------------
-- 统一资源表：景区/乡村/餐饮/住宿/交通/购物 全部共用
-- 这是"多业态融合"在数据层的落点——不是六张表，是一张表加一个类型字段
-- ------------------------------------------------------------
DROP TABLE IF EXISTS poi;
CREATE TABLE poi (
  id            VARCHAR(32)  NOT NULL COMMENT '资源编码，如 P-SCE-001',
  city_code     VARCHAR(32)  NOT NULL COMMENT '所属城市',
  name          VARCHAR(128) NOT NULL COMMENT '名称',
  business_type VARCHAR(20)  NOT NULL
                COMMENT 'SCENIC 景区 / RURAL_SPOT 乡村旅游 / FOOD 餐饮 / LODGING 住宿 / TRANSPORT 交通 / SHOPPING 购物',
  district      VARCHAR(64)           COMMENT '所属区县',
  level         VARCHAR(64)           COMMENT '等级：A 级景区 / 示范村 / 地理标志等',
  lng           DECIMAL(10,6)         COMMENT '经度（估算）',
  lat           DECIMAL(10,6)         COMMENT '纬度（估算）',
  ticket_price  DECIMAL(10,2) DEFAULT 0 COMMENT '门票/人均价格，0 表示免费',
  open_hours    VARCHAR(64)           COMMENT '开放时间',
  duration_min  INT           DEFAULT 0 COMMENT '建议游玩时长（分钟）',
  capacity      INT           DEFAULT 0 COMMENT '设计承载上限（估算值）',
  tags          VARCHAR(255)          COMMENT '标签，英文逗号分隔',
  summary       VARCHAR(512)          COMMENT '简介',
  scene         VARCHAR(20)           COMMENT '封面插画标识：qinling/terrace/rapeseed/ancient/river',
  data_origin   VARCHAR(16)  DEFAULT 'PUBLIC' COMMENT 'PUBLIC 公开资料 / SYNTHETIC 仿真',
  source_url    VARCHAR(255)          COMMENT '来源链接',
  status        TINYINT      DEFAULT 1 COMMENT '1 上架 / 0 下架',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_city_type (city_code, business_type),
  KEY idx_district (district),
  KEY idx_name (name)
) ENGINE=InnoDB COMMENT='文旅资源点（多业态统一模型）';

-- ------------------------------------------------------------
-- 资源关系：把"资源"变成"网络"
-- NEARBY 邻近 / SUPPORT 配套 / SAME_VILLAGE 同村 / DIVERSION 可分流承接
-- ------------------------------------------------------------
DROP TABLE IF EXISTS poi_relation;
CREATE TABLE poi_relation (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  city_code     VARCHAR(32)  NOT NULL COMMENT '所属城市',
  from_poi_id   VARCHAR(32)  NOT NULL COMMENT '起点资源',
  to_poi_id     VARCHAR(32)  NOT NULL COMMENT '终点资源',
  relation_type VARCHAR(20)  NOT NULL COMMENT 'NEARBY / SUPPORT / SAME_VILLAGE / DIVERSION',
  distance_km   DECIMAL(8,2)          COMMENT '球面距离（公里）',
  travel_min    INT                   COMMENT '预计通行时间（分钟，按 40km/h 估算）',
  weight        DECIMAL(5,2) DEFAULT 1.00 COMMENT '关系权重，用于推荐排序',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_relation (from_poi_id, to_poi_id, relation_type),
  KEY idx_from (from_poi_id, relation_type),
  KEY idx_to (to_poi_id)
) ENGINE=InnoDB COMMENT='资源关系（多业态融合的技术载体）';
