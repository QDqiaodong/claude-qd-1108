SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 葡萄酒窖 · 酒款 / 窖藏批次 / 会员 / 销售出窖
-- 库名 wine_cellar；application.yml 里 ddl-auto: none，表结构全部由本文件与 mysql/schema.sql 保持一致（构建期导入）。
-- 注意：mysql 容器是 UTC，时间戳一律写 NOW()，日期写具体日期或 CURDATE()。
--
-- 联动口径（窖藏批次放行 ↔ 销售可用资格）：
--   1. cellar_batch.available_bottles：可售批次剩余可用瓶数；窖藏中/已售罄批次恒为 0。
--   2. wine.stock 恒等于该酒款所有「可售」批次 available_bottles 之和，
--      放行 +瓶数、出窖按入窖日最早(FEFO)扣批次、退窖按 sale_out_item.batch_id 原批次回补。
--   3. sale_out_item.batch_id 记录每一行实际扣减的窖藏批次，销售单不得只记酒款丢失批次来源。

CREATE DATABASE IF NOT EXISTS wine_cellar
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE wine_cellar;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS sale_out_item;
DROP TABLE IF EXISTS sale_out;
DROP TABLE IF EXISTS cellar_batch;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS wine;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 酒款 ----------------
CREATE TABLE wine (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL UNIQUE COMMENT '酒款编号 W-1001',
    name       VARCHAR(128) NOT NULL COMMENT '酒名',
    region     VARCHAR(16)  NOT NULL COMMENT '产区: 国产/法国/智利/澳洲/其他',
    type       VARCHAR(16)  NOT NULL COMMENT '类型: 红/白/起泡/甜',
    stock      INT          NOT NULL COMMENT '可售库存（= 各可售批次可用瓶数合计，联动不可直改）',
    price      DECIMAL(10,2) NOT NULL COMMENT '单价',
    status     VARCHAR(16)  NOT NULL COMMENT '在售/停售',
    created_at DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 窖藏批次 ----------------
CREATE TABLE cellar_batch (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    wine_id           BIGINT       NOT NULL COMMENT '关联酒款 id',
    batch_no          VARCHAR(32)  NOT NULL UNIQUE COMMENT '批次号 B-2025-01',
    bottle_count      INT          NOT NULL COMMENT '入窖瓶数',
    available_bottles INT          NOT NULL DEFAULT 0 COMMENT '剩余可用瓶数（可售批次随出窖/退窖联动，窖藏中/已售罄为 0）',
    cellar_date       DATE         NOT NULL COMMENT '入窖日（FEFO 先后依据）',
    status            VARCHAR(16)  NOT NULL COMMENT '窖藏中/可售/已售罄',
    created_at        DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员 ----------------
CREATE TABLE member (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone      VARCHAR(24)  NOT NULL UNIQUE COMMENT '手机号',
    name       VARCHAR(32)  NOT NULL COMMENT '昵称',
    level      VARCHAR(16)  NOT NULL COMMENT '普通/银卡/黑卡',
    points     INT          NOT NULL DEFAULT 0 COMMENT '积分',
    created_at DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 销售出窖 ----------------
CREATE TABLE sale_out (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT       COMMENT '关联会员 id（散客可空）',
    total      DECIMAL(12,2) NOT NULL COMMENT '出窖总额',
    status     VARCHAR(16)  NOT NULL COMMENT '已出窖/已退窖',
    created_at DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sale_out_item (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_id  BIGINT NOT NULL COMMENT '关联出窖单 id',
    wine_id  BIGINT NOT NULL COMMENT '关联酒款 id',
    batch_id BIGINT NOT NULL COMMENT '实际扣减的窖藏批次 id（退窖按此行原批次回补）',
    qty      INT    NOT NULL COMMENT '数量'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据（wine.stock 与各可售批次可用瓶数严格对齐）
-- =====================================================================

-- 酒款：含 1 个停售异常态（W-1007，无批次、库存 0）
INSERT INTO wine (id, code, name, region, type, stock, price, status, created_at) VALUES
(1, 'W-1001', '张裕解百纳干红',   '国产', '红',   120, 198.00, '在售', NOW()),
(2, 'W-1002', '长城五星赤霞珠',   '国产', '红',    80, 268.00, '在售', NOW()),
(3, 'W-1003', '拉菲传奇波尔多',   '法国', '红',    68, 458.00, '在售', NOW()),
(4, 'W-1004', '奔富 Bin8 设拉子', '澳洲', '红',    50, 358.00, '在售', NOW()),
(5, 'W-1005', '干露红魔鬼卡本妮', '智利', '红',    90, 158.00, '在售', NOW()),
(6, 'W-1006', '酩悦香槟',         '法国', '起泡',  40, 520.00, '在售', NOW()),
(7, 'W-1007', '老藤贵腐甜白',     '其他', '甜',     0, 320.00, '停售', NOW()),
(8, 'W-1008', '白诗南干白',       '法国', '白',    70, 210.00, '在售', NOW());

-- 窖藏批次：
--   窖藏中 3 个（id 1/4/12，可放行）、已售罄 1 个（id 3）、其余可售；
--   酒款 1/3/5 各有多个可售批次，入窖日不同，可演示 FEFO 最早批次优先扣减。
--   可用瓶数已扣除历史出窖单：#1 拉菲 x2 扣批次 2；#3 张裕 x2 扣批次 5；
--   #2 散客退窖单已把 1 瓶干露红魔鬼回补进批次 10。
INSERT INTO cellar_batch (id, wine_id, batch_no, bottle_count, available_bottles, cellar_date, status, created_at) VALUES
( 1, 1, 'B-2025-01', 50,  0, '2025-03-12', '窖藏中', NOW()),
( 2, 3, 'B-2025-02', 30, 28, '2025-05-20', '可售',   NOW()),
( 3, 4, 'B-2025-03', 20,  0, '2025-06-15', '已售罄', NOW()),
( 4, 5, 'B-2025-04', 40,  0, '2025-07-01', '窖藏中', NOW()),
( 5, 1, 'B-2025-05', 80, 78, '2025-07-10', '可售',   NOW()),
( 6, 2, 'B-2025-06', 80, 80, '2025-06-20', '可售',   NOW()),
( 7, 3, 'B-2025-07', 40, 40, '2025-06-25', '可售',   NOW()),
( 8, 4, 'B-2025-08', 50, 50, '2025-08-01', '可售',   NOW()),
( 9, 1, 'B-2025-09', 50, 42, '2025-08-12', '可售',   NOW()),
(10, 5, 'B-2025-10', 60, 60, '2025-08-05', '可售',   NOW()),
(11, 5, 'B-2025-11', 30, 30, '2025-08-10', '可售',   NOW()),
(12, 3, 'B-2025-12', 20,  0, '2025-08-18', '窖藏中', NOW()),
(13, 6, 'B-2025-13', 40, 40, '2025-08-22', '可售',   NOW()),
(14, 8, 'B-2025-14', 70, 70, '2025-08-25', '可售',   NOW());

-- 会员：普通 / 银卡 / 黑卡 齐备
INSERT INTO member (id, phone, name, level, points, created_at) VALUES
(1, '13800001111', '张伟', '普通', 1200, NOW()),
(2, '13800002222', '李娜', '银卡', 2600, NOW()),
(3, '13800003333', '王芳', '黑卡', 7200, NOW()),
(4, '13800004444', '刘洋', '普通',  300, NOW());

-- 销售出窖：含 1 个已退窖异常态（散客退窖，积分本就未累计）
INSERT INTO sale_out (id, member_id, total, status, created_at) VALUES
(1, 3, 916.00, '已出窖', NOW()),     -- 王芳(黑卡)：拉菲 x2 = 916
(2, NULL, 158.00, '已退窖', NOW()),  -- 散客：干露红魔鬼 x1，已退窖
(3, 1, 396.00, '已出窖', NOW());     -- 张伟(普通)：张裕 x2 = 396

-- 出窖明细：每行都带实际扣减批次（退窖据此逐项原批次回补）
INSERT INTO sale_out_item (id, sale_id, wine_id, batch_id, qty) VALUES
(1, 1, 3,  2, 2),   -- 拉菲 2 瓶 ← 批次 B-2025-02
(2, 2, 5, 10, 1),   -- 干露红魔鬼 1 瓶 ← 批次 B-2025-10（已退窖回补）
(3, 3, 1,  5, 2);   -- 张裕 2 瓶 ← 批次 B-2025-05
