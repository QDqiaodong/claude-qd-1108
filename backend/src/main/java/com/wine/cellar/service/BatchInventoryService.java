package com.wine.cellar.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.WineRepository;

/**
 * 窖藏批次 ↔ 酒款库存联动领域服务。
 *
 * 库存口径：wine.stock 恒等于该酒款所有「可售」批次 available_bottles 之和。
 * 所有会改变瓶数的动作（放行 / 出窖扣减 / 退窖回补）都经由这里，且必须在调用方事务内、
 * 持锁执行，保证：不跳跃、不重复、不超扣、不留半成功数据。
 *
 * 并发口径：权威的“批次可用合计”一律取行锁当前读（findSellableWithLock），
 * 不使用事务早期的快照读结果，避免 MySQL 可重复读下一级缓存返回陈旧值造成丢失更新。
 */
@Service
public class BatchInventoryService {

    private final CellarBatchRepository batchRepository;
    private final WineRepository wineRepository;

    public BatchInventoryService(CellarBatchRepository batchRepository, WineRepository wineRepository) {
        this.batchRepository = batchRepository;
        this.wineRepository = wineRepository;
    }

    /** 一次批次扣减结果：实际动了哪些批次、各扣多少 */
    public static class AllocationLine {
        public Long batchId;
        public String batchNo;
        public int qty;

        public AllocationLine(Long batchId, String batchNo, int qty) {
            this.batchId = batchId;
            this.batchNo = batchNo;
            this.qty = qty;
        }
    }

    /** 某酒款当前可售总量（聚合只读，供页面展示与提交前预检；权威判断以锁定当前读为准） */
    public int availableSum(Long wineId) {
        Long v = batchRepository.sumAvailableByWineIdAndStatus(wineId, "可售");
        return v == null ? 0 : v;
    }

    /** 按 id 取批次（可能已不存在），供出窖明细拼批次号 */
    public java.util.Optional<CellarBatch> findBatchIfExists(Long batchId) {
        return batchRepository.findById(batchId);
    }

    /**
     * 行锁当前读校验台账与批次是否一致：wine.stock 必须等于可售批次可用瓶数合计。
     * 不一致时抛出“可理解”的业务错误，只拦截该酒款的流转，其他酒款仍可继续处理。
     * 返回加锁后的可售批次集合（当前读），供调用方继续处理。
     */
    public List<CellarBatch> lockSellableAndAssertConsistent(Wine lockedWine) {
        List<CellarBatch> sellable = batchRepository.findSellableWithLock(lockedWine.id, "可售");
        int sum = sellable.stream().mapToInt(b -> nz(b.availableBottles)).sum();
        if (nz(lockedWine.stock) != sum) {
            throw new BizException("酒款「" + lockedWine.name + "」台账库存(" + nz(lockedWine.stock)
                    + ") 与各可售批次可用瓶数合计(" + sum + ") 不一致，已暂停该酒款的放行/出窖/退窖；"
                    + "请在窖藏批次页核对后再操作，其他酒款不受影响");
        }
        return sellable;
    }

    /**
     * 放行：窖藏中 → 可售。瓶数全额计入可用瓶数并同步增加酒款库存。
     * 调用方已按 酒款→批次 顺序加好锁；此处做权威状态与一致性判断。
     */
    @Transactional
    public CellarBatch release(CellarBatch lockedBatch, Wine lockedWine) {
        if (!"窖藏中".equals(lockedBatch.status)) {
            throw new BizException("仅「窖藏中」的批次可放行，当前为「" + lockedBatch.status + "」");
        }
        lockSellableAndAssertConsistent(lockedWine);
        lockedBatch.availableBottles = lockedBatch.bottleCount;
        lockedBatch.status = "可售";
        lockedWine.stock = nz(lockedWine.stock) + lockedBatch.bottleCount;
        batchRepository.save(lockedBatch);
        wineRepository.save(lockedWine);
        return lockedBatch;
    }

    /**
     * FEFO 扣减：按入窖日最早的可售批次优先扣（同日按批次 id），跨批次拆扣。
     * 调用方需先锁酒款；本方法以行锁当前读取得可售批次集合，
     * 并发的另一笔出窖会等待同一批次锁，不会出现“批次已被其他操作扣完仍继续扣”的半成功结果。
     *
     * @return 实际扣减明细（逐批次），用于落 sale_out_item，绝不只记酒款丢失来源
     */
    @Transactional
    public List<AllocationLine> deduct(Wine lockedWine, int qty) {
        List<CellarBatch> sellable = lockSellableAndAssertConsistent(lockedWine);
        int total = sellable.stream().mapToInt(b -> nz(b.availableBottles)).sum();
        if (total < qty) {
            throw new BizException("酒款「" + lockedWine.name + "」可售批次库存不足（仅剩 " + total + " 瓶）");
        }

        List<AllocationLine> lines = new ArrayList<>();
        int remain = qty;
        for (CellarBatch b : sellable) {
            if (remain <= 0) {
                break;
            }
            int take = Math.min(nz(b.availableBottles), remain);
            if (take <= 0) {
                continue;
            }
            b.availableBottles -= take;
            if (b.availableBottles == 0) {
                b.status = "已售罄"; // 批次扣完自动售罄
            }
            batchRepository.save(b);
            lines.add(new AllocationLine(b.id, b.batchNo, take));
            remain -= take;
        }
        if (remain > 0) {
            // 理论不可达（已基于当前读预检总量），兜底防止半成功
            throw new BizException("酒款「" + lockedWine.name + "」批次扣减异常，请重试");
        }

        lockedWine.stock = nz(lockedWine.stock) - qty;
        wineRepository.save(lockedWine);
        return lines;
    }

    /**
     * 退窖回补：按原出窖明细逐项（batch_id + qty）回补到原批次，
     * 已售罄批次回补后恢复「可售」，酒款库存同步加回。
     */
    @Transactional
    public void restore(Wine lockedWine, List<AllocationLine> lines) {
        // 先以当前读锁定该酒款可售批次并校验一致
        lockSellableAndAssertConsistent(lockedWine);

        // 同一批次多行先合并，避免恢复中途状态判断混乱
        Map<Long, Integer> byBatch = new LinkedHashMap<>();
        for (AllocationLine l : lines) {
            byBatch.merge(l.batchId, l.qty, Integer::sum);
        }

        int restored = 0;
        for (Map.Entry<Long, Integer> e : byBatch.entrySet()) {
            CellarBatch b = batchRepository.findWithLockById(e.getKey())
                    .orElseThrow(() -> new BizException(
                            "原出窖批次已不存在，无法按原批次回补，请在窖藏批次页核对数据"));
            if (!b.wineId.equals(lockedWine.id)) {
                throw new BizException("退窖明细批次「" + b.batchNo + "」与酒款不匹配，数据异常已拦截");
            }
            int qty = e.getValue();
            int after = nz(b.availableBottles) + qty;
            if (after > nz(b.bottleCount)) {
                throw new BizException("回补到批次「" + b.batchNo + "」后将超过其入窖瓶数("
                        + b.bottleCount + ")，台账与批次可能已不一致，已拦截本次退窖");
            }
            b.availableBottles = after;
            if ("已售罄".equals(b.status)) {
                b.status = "可售"; // 有可用瓶数即恢复可售
            } else if (!"可售".equals(b.status)) {
                throw new BizException("批次「" + b.batchNo + "」当前为「" + b.status
                        + "」，不能回补，状态流转异常已拦截");
            }
            batchRepository.save(b);
            restored += qty;
        }

        lockedWine.stock = nz(lockedWine.stock) + restored;
        wineRepository.save(lockedWine);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
