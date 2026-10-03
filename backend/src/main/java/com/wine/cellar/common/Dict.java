package com.wine.cellar.common;

import java.util.List;

/**
 * 葡萄酒窖业务词典与规则参数表：酒款产区 / 类型 / 状态、窖藏批次状态与流转、
 * 会员等级、销售出窖状态。规则集中在此，便于出题时被要求调整或扩展。
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 酒款 wine ----------------
    /** 产区：国产 / 法国 / 智利 / 澳洲 / 其他 */
    public static final List<String> WINE_REGIONS = List.of("国产", "法国", "智利", "澳洲", "其他");

    /** 类型：红 / 白 / 起泡 / 甜 */
    public static final List<String> WINE_TYPES = List.of("红", "白", "起泡", "甜");

    /** 状态：在售 / 停售 */
    public static final List<String> WINE_STATUS = List.of("在售", "停售");

    // ---------------- 窖藏批次 cellar_batch ----------------
    /** 窖藏批次状态：窖藏中 / 可售 / 已售罄，逐级推进，不可跳级 */
    public static final List<String> CELLAR_BATCH_STATUS = List.of("窖藏中", "可售", "已售罄");

    /** 窖藏批次状态机流转：窖藏中 → 可售（release）→ 已售罄 */
    public static final List<String> CELLAR_BATCH_FLOW = List.of("窖藏中", "可售", "已售罄");

    // ---------------- 会员 member ----------------
    /** 会员等级：普通 / 银卡 / 黑卡 */
    public static final List<String> MEMBER_LEVELS = List.of("普通", "银卡", "黑卡");

    /** 会员累计积分对应等级阈值（达到即升级） */
    public static final int SILVER_POINTS = 2000;

    public static final int BLACK_POINTS = 5000;

    // ---------------- 销售出窖 sale_out ----------------
    /** 出窖状态：已出窖 / 已退窖 */
    public static final List<String> SALE_OUT_STATUS = List.of("已出窖", "已退窖");

    // ---------------- 校验方法 ----------------

    public static boolean isValidWineRegion(String region) {
        return region != null && WINE_REGIONS.contains(region);
    }

    public static boolean isValidWineType(String type) {
        return type != null && WINE_TYPES.contains(type);
    }

    public static boolean isValidWineStatus(String status) {
        return status != null && WINE_STATUS.contains(status);
    }

    public static boolean isValidCellarBatchStatus(String status) {
        return status != null && CELLAR_BATCH_STATUS.contains(status);
    }

    /** 窖藏批次状态只能逐级推进：窖藏中 → 可售 → 已售罄 */
    public static boolean canTransferBatch(String from, String to) {
        int i = CELLAR_BATCH_FLOW.indexOf(from);
        return i >= 0 && i + 1 < CELLAR_BATCH_FLOW.size() && CELLAR_BATCH_FLOW.get(i + 1).equals(to);
    }

    public static boolean isValidMemberLevel(String level) {
        return level != null && MEMBER_LEVELS.contains(level);
    }

    /** 按累计积分自动判定会员等级 */
    public static String decideMemberLevel(int points) {
        if (points >= BLACK_POINTS) {
            return "黑卡";
        }
        if (points >= SILVER_POINTS) {
            return "银卡";
        }
        return "普通";
    }

    public static boolean isValidSaleOutStatus(String status) {
        return status != null && SALE_OUT_STATUS.contains(status);
    }
}
