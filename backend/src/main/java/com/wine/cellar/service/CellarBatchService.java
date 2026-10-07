package com.wine.cellar.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.WineRepository;

@Service
public class CellarBatchService {

    private final CellarBatchRepository batchRepository;
    private final WineRepository wineRepository;
    private final BatchInventoryService inventoryService;

    public CellarBatchService(CellarBatchRepository batchRepository,
                              WineRepository wineRepository,
                              BatchInventoryService inventoryService) {
        this.batchRepository = batchRepository;
        this.wineRepository = wineRepository;
        this.inventoryService = inventoryService;
    }

    // ---------------- 查询 ----------------
    public List<CellarBatch> list(String status) {
        List<CellarBatch> all = batchRepository.findAll();
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(b -> status.equals(b.status)).toList();
        }
        return all;
    }

    public CellarBatch get(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("窖藏批次不存在"));
    }

    // ---------------- 新建 ----------------
    public static class BatchInput {
        public Long wineId;
        public String batchNo;
        public Integer bottleCount;
        public java.time.LocalDate cellarDate;
        /** 状态仅为兼容旧入参保留：新建一律「窖藏中」，不允许直接建成可售/已售罄 */
        public String status;
    }

    @Transactional
    public CellarBatch create(BatchInput input) {
        if (input.wineId == null) {
            throw new BizException("关联酒款不能为空");
        }
        Wine wine = wineRepository.findById(input.wineId)
                .orElseThrow(() -> new BizException("关联酒款不存在"));
        if (input.batchNo == null || input.batchNo.isBlank()) {
            throw new BizException("批次号不能为空");
        }
        if (batchRepository.existsByBatchNo(input.batchNo)) {
            throw new BizException("批次号已存在，不可重复");
        }
        if (input.bottleCount == null || input.bottleCount <= 0) {
            throw new BizException("瓶数必须大于 0");
        }
        if (input.cellarDate == null) {
            throw new BizException("入窖日不能为空");
        }
        if (input.status != null && !input.status.isBlank() && !"窖藏中".equals(input.status)) {
            throw new BizException("新建批次只能是「窖藏中」，放行后才可进入「可售」，不允许跳跃建单");
        }

        CellarBatch b = new CellarBatch();
        b.wineId = wine.id;
        b.batchNo = input.batchNo;
        b.bottleCount = input.bottleCount;
        b.availableBottles = 0;   // 窖藏中不贡献可售库存
        b.cellarDate = input.cellarDate;
        b.status = "窖藏中";
        return batchRepository.save(b);
    }

    // ---------------- 更新（部分字段） ----------------
    @Transactional
    public CellarBatch update(Long id, BatchInput input) {
        CellarBatch b = batchRepository.findWithLockById(id)
                .orElseThrow(() -> new BizException("窖藏批次不存在"));
        if (input.wineId != null && !input.wineId.equals(b.wineId)) {
            if (!"窖藏中".equals(b.status)) {
                throw new BizException("批次已放行（" + b.status + "），不可改挂其他酒款");
            }
            Wine wine = wineRepository.findById(input.wineId)
                    .orElseThrow(() -> new BizException("关联酒款不存在"));
            b.wineId = wine.id;
        }
        if (input.batchNo != null) {
            if (input.batchNo.isBlank()) {
                throw new BizException("批次号不能为空");
            }
            if (!input.batchNo.equals(b.batchNo) && batchRepository.existsByBatchNoAndIdNot(input.batchNo, id)) {
                throw new BizException("批次号已存在，不可重复");
            }
            b.batchNo = input.batchNo;
        }
        if (input.bottleCount != null) {
            if (input.bottleCount <= 0) {
                throw new BizException("瓶数必须大于 0");
            }
            // 只有未放行的窖藏批次能改瓶数；放行后瓶数联动库存，只能经出窖/退窖变化
            if (!"窖藏中".equals(b.status)) {
                throw new BizException("批次已" + b.status + "，瓶数随销售/退窖联动，不可直接修改");
            }
            b.bottleCount = input.bottleCount;
        }
        if (input.cellarDate != null) {
            b.cellarDate = input.cellarDate;
        }
        if (input.status != null && !input.status.isBlank() && !input.status.equals(b.status)) {
            throw new BizException("批次状态不可直接修改：放行用「放行可售」，售罄由出窖自动完成，退窖自动恢复可售");
        }
        return batchRepository.save(b);
    }

    // ---------------- 动作：窖藏中 → 可售 ----------------
    /** 放行：仅「窖藏中」可放行，瓶数全额转入可售库存并同步酒款库存（持锁，拒绝重复放行） */
    @Transactional
    public CellarBatch release(Long id) {
        // 先用投影拿到酒款 id（不把批次实体装入一级缓存），再统一按 酒款→批次 顺序加锁，
        // 避免与出窖/退窖死锁，也避免快照陈旧值导致重复放行漏判。
        Long wineId = batchRepository.findWineIdById(id)
                .orElseThrow(() -> new BizException("窖藏批次不存在"));
        Wine wine = wineRepository.findWithLockById(wineId)
                .orElseThrow(() -> new BizException("关联酒款不存在"));
        CellarBatch locked = batchRepository.findWithLockById(id)
                .orElseThrow(() -> new BizException("窖藏批次不存在"));
        if (!"窖藏中".equals(locked.status)) {
            throw new BizException("仅「窖藏中」的批次可放行，当前为「" + locked.status + "」，请勿重复操作");
        }
        return inventoryService.release(locked, wine);
    }
}
