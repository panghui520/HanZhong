-- ============================================================
-- M2 乡村体验与农产品
-- 依赖 M1 的 poi 表：experience.poi_id、product.poi_id 均指向 poi.id
-- 执行：mysql -uroot -p < db/V2__m2_experience_product.sql
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 产品分类。parent_code 留给后续二级分类，当前数据只有一级
-- ------------------------------------------------------------
DROP TABLE IF EXISTS product_category;
CREATE TABLE product_category (
  code        VARCHAR(32)  NOT NULL COMMENT '分类编码，如 CAT-TEA',
  city_code   VARCHAR(32)  NOT NULL COMMENT '所属城市',
  name        VARCHAR(64)  NOT NULL COMMENT '分类名，如 茶叶',
  parent_code VARCHAR(32)           COMMENT '父分类编码，NULL 表示一级分类',
  sort        INT          DEFAULT 0 COMMENT '排序，越小越靠前',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (code),
  KEY idx_city_sort (city_code, sort)
) ENGINE=InnoDB COMMENT='农产品分类';

-- ------------------------------------------------------------
-- 乡村体验项目：游客在乡村点"可以做的事"
-- 它是整条消费链的锚点——每一款产品都要能追溯到一次具体体验
-- ------------------------------------------------------------
DROP TABLE IF EXISTS experience;
CREATE TABLE experience (
  id           VARCHAR(32)   NOT NULL COMMENT '体验编码，如 E-001',
  city_code    VARCHAR(32)   NOT NULL COMMENT '所属城市',
  poi_id       VARCHAR(32)   NOT NULL COMMENT '所属乡村点（-> poi.id）',
  name         VARCHAR(128)  NOT NULL COMMENT '体验名称',
  type         VARCHAR(20)   NOT NULL
               COMMENT 'TEA 采茶 / PICKING 采摘 / FOLK 非遗手作 / HOMESTAY 民宿 / FOOD_MAKING 农事美食 / NATURE 自然观察',
  duration_min INT           DEFAULT 0 COMMENT '体验时长（分钟）',
  price        DECIMAL(10,2) DEFAULT 0 COMMENT '人均价格，0 表示免费',
  season       VARCHAR(32)            COMMENT '适宜季节，如 3月-5月',
  capacity     INT           DEFAULT 0 COMMENT '单场可接待人数',
  tags         VARCHAR(255)           COMMENT '标签，英文逗号分隔',
  -- 列名不叫 desc：DESC 是 SQL 保留字。对外 JSON 仍是 desc，由 VO 负责转回
  description  VARCHAR(512)           COMMENT '体验说明',
  data_origin  VARCHAR(16)   DEFAULT 'PUBLIC' COMMENT 'PUBLIC 公开资料 / SYNTHETIC 仿真',
  source_url   VARCHAR(255)           COMMENT '来源链接',
  status       TINYINT       DEFAULT 1 COMMENT '1 上架 / 0 下架',
  created_at   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_poi (poi_id),
  KEY idx_city_type (city_code, type)
) ENGINE=InnoDB COMMENT='乡村体验项目（消费链的体验锚点）';

-- ------------------------------------------------------------
-- 乡村农产品
--
-- CHECK 约束是设计红线在数据层的落点：产品必须能追溯，
-- 产地与体验锚点至少一项非空。所以库里不可能存在"孤立商品"，
-- 前端也就不存在独立商城页面的数据基础。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS product;
CREATE TABLE product (
  id             VARCHAR(32)   NOT NULL COMMENT '产品编码，如 PRD-001',
  city_code      VARCHAR(32)   NOT NULL COMMENT '所属城市',
  poi_id         VARCHAR(32)            COMMENT '产地乡村点（-> poi.id）',
  experience_id  VARCHAR(32)            COMMENT '体验锚点（-> experience.id）',
  category_code  VARCHAR(32)   NOT NULL COMMENT '分类（-> product_category.code）',
  name           VARCHAR(128)  NOT NULL COMMENT '产品名称',
  spec           VARCHAR(64)            COMMENT '规格，如 100g / 罐',
  price          DECIMAL(10,2) DEFAULT 0 COMMENT '售价',
  origin_village VARCHAR(64)            COMMENT '产地村',
  stock          INT           DEFAULT 0 COMMENT '库存（仿真值）',
  tags           VARCHAR(255)           COMMENT '标签，英文逗号分隔',
  story          VARCHAR(512)           COMMENT '溯源文案：这一款和那次体验的关系',
  scene          VARCHAR(20)            COMMENT '封面插画标识',
  data_origin    VARCHAR(16)   DEFAULT 'PUBLIC' COMMENT 'PUBLIC 公开资料 / SYNTHETIC 仿真',
  source_url     VARCHAR(255)           COMMENT '来源链接',
  status         TINYINT       DEFAULT 1 COMMENT '1 上架 / 0 下架',
  created_at     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_poi (poi_id),
  KEY idx_experience (experience_id),
  KEY idx_city_category (city_code, category_code),
  CONSTRAINT chk_product_traceable CHECK (poi_id IS NOT NULL OR experience_id IS NOT NULL)
) ENGINE=InnoDB COMMENT='乡村农产品（必须挂靠产地或体验锚点）';
