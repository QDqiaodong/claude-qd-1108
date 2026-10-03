package com.wine.cellar.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.WineRepository;

@Service
public class CellarBatchService {

    private final CellarBatchRepository batchRepository;
    private final WineRepository wineRepository;

    public CellarBatchService(CellarBatchRepository batchRepository, WineRepository wineRepository) {
        this.batchRepository = batchRepository;
        this.wineRepository = wineRepository;
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
        public String status;
    }

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

        CellarBatch b = new CellarBatch();
        b.wineId = wine.id;
        b.batchNo = input.batchNo;
        b.bottleCount = input.bottleCount;
        b.cellarDate = input.cellarDate;
        b.status = (input.status == null || input.status.isBlank()) ? "窖藏中" : input.status;
        if (!Dict.isValidCellarBatchStatus(b.status)) {
            throw new BizException("状态必须是：窖藏中/可售/已售罄 之一");
        }
        return batchRepository.save(b);
    }

    // ---------------- 更新（部分字段） ----------------
    public CellarBatch update(Long id, BatchInput input) {
        CellarBatch b = get(id);
        if (input.wineId != null) {
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
            b.bottleCount = input.bottleCount;
        }
        if (input.cellarDate != null) {
            b.cellarDate = input.cellarDate;
        }
        if (input.status != null) {
            if (!Dict.isValidCellarBatchStatus(input.status)) {
                throw new BizException("状态必须是：窖藏中/可售/已售罄 之一");
            }
            b.status = input.status;
        }
        return batchRepository.save(b);
    }

    // ---------------- 动作：放的可售 ----------------
    /** 窖藏中 → 可售（状态机前进） */
    public CellarBatch release(Long id) {
        CellarBatch b = get(id);
        if (!Dict.canTransferBatch(b.status, "可售")) {
            throw new BizException("仅「窖藏中」的批次可放行，当前为「" + b.status + "」");
        }
        b.status = "可售";
        return batchRepository.save(b);
    }
}
