SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 葡萄酒窖 · 酒款 / 窖藏批次 / 会员 / 销售出窖
-- 库名 wine_cellar；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，时间戳一律写 NOW()，日期写具体日期或 CURDATE()。

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
    stock      INT          NOT NULL COMMENT '库存',
    price      DECIMAL(10,2) NOT NULL COMMENT '单价',
    status     VARCHAR(16)  NOT NULL COMMENT '在售/停售',
    created_at DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 窖藏批次 ----------------
CREATE TABLE cellar_batch (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    wine_id     BIGINT       NOT NULL COMMENT '关联酒款 id',
    batch_no    VARCHAR(32)  NOT NULL UNIQUE COMMENT '批次号 B-2025-01',
    bottle_count INT         NOT NULL COMMENT '瓶数',
    cellar_date DATE         NOT NULL COMMENT '入窖日',
    status      VARCHAR(16)  NOT NULL COMMENT '窖藏中/可售/已售罄',
    created_at  DATETIME     COMMENT '创建时间'
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
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_id BIGINT NOT NULL COMMENT '关联出窖单 id',
    wine_id BIGINT NOT NULL COMMENT '关联酒款 id',
    qty     INT    NOT NULL COMMENT '数量'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

-- 酒款：含 1 个停售异常态（W-1007）
INSERT INTO wine (id, code, name, region, type, stock, price, status, created_at) VALUES
(1, 'W-1001', '张裕解百纳干红',   '国产', '红',   120, 198.00, '在售', NOW()),
(2, 'W-1002', '长城五星赤霞珠',   '国产', '红',    80, 268.00, '在售', NOW()),
(3, 'W-1003', '拉菲传奇波尔多',   '法国', '红',    60, 458.00, '在售', NOW()),
(4, 'W-1004', '奔富 Bin8 设拉子', '澳洲', '红',    50, 358.00, '在售', NOW()),
(5, 'W-1005', '干露红魔鬼卡本妮', '智利', '红',    90, 158.00, '在售', NOW()),
(6, 'W-1006', '酩悦香槟',         '法国', '起泡',  40, 520.00, '在售', NOW()),
(7, 'W-1007', '老藤贵腐甜白',     '其他', '甜',     0, 320.00, '停售', NOW()),
(8, 'W-1008', '白诗南干白',       '法国', '白',    70, 210.00, '在售', NOW());

-- 窖藏批次：含 2 个窖藏中（可 release）、1 个可售、1 个已售罄
INSERT INTO cellar_batch (id, wine_id, batch_no, bottle_count, cellar_date, status, created_at) VALUES
(1, 1, 'B-2025-01', 50, '2025-03-12', '窖藏中', NOW()),
(2, 3, 'B-2025-02', 30, '2025-05-20', '可售',   NOW()),
(3, 4, 'B-2025-03', 20, '2025-06-15', '已售罄', NOW()),
(4, 5, 'B-2025-04', 40, '2025-07-01', '窖藏中', NOW());

-- 会员：普通 / 银卡 / 黑卡 齐备
INSERT INTO member (id, phone, name, level, points, created_at) VALUES
(1, '13800001111', '张伟', '普通', 1200, NOW()),
(2, '13800002222', '李娜', '银卡', 2600, NOW()),
(3, '13800003333', '王芳', '黑卡', 7200, NOW()),
(4, '13800004444', '刘洋', '普通',  300, NOW());

-- 销售出窖：含 1 个已退窖异常态（散客退窖）
INSERT INTO sale_out (id, member_id, total, status, created_at) VALUES
(1, 3, 916.00, '已出窖', NOW()),     -- 王芳(黑卡)：拉菲 x2 = 916
(2, NULL, 158.00, '已退窖', NOW()),  -- 散客：干露红魔鬼 x1，已退窖
(3, 1, 396.00, '已出窖', NOW());     -- 张伟(普通)：张裕 x2 = 396

INSERT INTO sale_out_item (id, sale_id, wine_id, qty) VALUES
(1, 1, 3, 2),
(2, 2, 5, 1),
(3, 3, 1, 2);
