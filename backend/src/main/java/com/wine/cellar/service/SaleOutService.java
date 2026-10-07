package com.wine.cellar.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.SaleOutItem;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.SaleOutItemRepository;
import com.wine.cellar.repository.SaleOutRepository;
import com.wine.cellar.repository.WineRepository;
import com.wine.cellar.service.BatchInventoryService.AllocationLine;

@Service
public class SaleOutService {

    private final SaleOutRepository saleRepository;
    private final SaleOutItemRepository itemRepository;
    private final WineRepository wineRepository;
    private final BatchInventoryService inventoryService;
    private final MemberService memberService;

    public SaleOutService(SaleOutRepository saleRepository,
                          SaleOutItemRepository itemRepository,
                          WineRepository wineRepository,
                          BatchInventoryService inventoryService,
                          MemberService memberService) {
        this.saleRepository = saleRepository;
        this.itemRepository = itemRepository;
        this.wineRepository = wineRepository;
        this.inventoryService = inventoryService;
        this.memberService = memberService;
    }

    // ---------------- 返回视图（带批次来源的出窖明细） ----------------
    public static class SaleOutItemVO {
        public Long id;
        public Long wineId;
        public String wineName;
        public String wineCode;
        public Long batchId;
        public String batchNo;
        public Integer qty;
        public BigDecimal price;
        public BigDecimal amount;
    }

    public static class SaleOutVO {
        public Long id;
        public Long memberId;
        public String memberName;
        public BigDecimal total;
        public String status;
        public String createdAt;
        public List<SaleOutItemVO> items = new ArrayList<>();
    }

    // ---------------- 查询 ----------------
    public List<SaleOut> listRaw(String status) {
        List<SaleOut> all = saleRepository.findAll();
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(s -> status.equals(s.status)).toList();
        }
        return all;
    }

    public List<SaleOutVO> list(String status) {
        return listRaw(status).stream().map(this::toVO).toList();
    }

    public SaleOut get(Long id) {
        return saleRepository.findById(id)
                .orElseThrow(() -> new BizException("出窖单不存在"));
    }

    public List<SaleOutItem> itemEntities(Long saleId) {
        return itemRepository.findBySaleId(saleId);
    }

    private SaleOutVO toVO(SaleOut s) {
        SaleOutVO vo = new SaleOutVO();
        vo.id = s.id;
        vo.memberId = s.memberId;
        vo.total = s.total;
        vo.status = s.status;
        vo.createdAt = s.createdAt == null ? null : s.createdAt.toString();
        if (s.memberId != null) {
            memberService.findIfExists(s.memberId).ifPresent(m -> vo.memberName = m.name);
        }
        List<SaleOutItem> its = itemRepository.findBySaleId(s.id);
        for (SaleOutItem it : its) {
            SaleOutItemVO iv = new SaleOutItemVO();
            iv.id = it.id;
            iv.wineId = it.wineId;
            iv.batchId = it.batchId;
            iv.qty = it.qty;
            Wine w = wineRepository.findById(it.wineId).orElse(null);
            if (w != null) {
                iv.wineName = w.name;
                iv.wineCode = w.code;
                iv.price = w.price;
                iv.amount = w.price.multiply(BigDecimal.valueOf(it.qty));
            }
            inventoryService.findBatchIfExists(it.batchId).ifPresent(b -> iv.batchNo = b.batchNo);
            vo.items.add(iv);
        }
        return vo;
    }

    // ---------------- 入参 ----------------
    public static class SaleItemInput {
        public Long wineId;
        public Integer qty;
    }

    public static class SaleCreateInput {
        public Long memberId;                 // 可空（散客）
        public List<SaleItemInput> items;     // 出窖明细
    }

    // ---------------- 新建即出窖（FEFO 扣批次，全程事务） ----------------
    @Transactional
    public SaleOut create(SaleCreateInput input) {
        if (input.items == null || input.items.isEmpty()) {
            throw new BizException("出窖明细不能为空");
        }

        // 1. 入参校验并按酒款合并（同酒款多行合计后统一做 FEFO）
        Map<Long, Integer> qtyByWine = new LinkedHashMap<>();
        for (SaleItemInput it : input.items) {
            if (it.wineId == null) {
                throw new BizException("出窖明细缺少酒款");
            }
            if (it.qty == null || it.qty <= 0) {
                throw new BizException("出窖数量必须大于 0");
            }
            qtyByWine.merge(it.wineId, it.qty, Integer::sum);
        }

        // 关联会员（散客可空）；只校验存在，不预载实体，积分以加锁当前读累加
        boolean hasMember = input.memberId != null;
        if (hasMember) {
            memberService.assertExists(input.memberId);
        }

        // 2. 按酒款 id 升序持锁执行（统一加锁顺序，避免多酒款并发事务相互死锁）。
        //    在售 / 台账与批次一致 / 可售量充足 等权威判断全部在锁后以当前读完成；
        //    任一明细不满足 → 抛异常整单回滚，绝不留下半成功数据，也不会被并发扣减形成丢失更新。
        List<Long> wineIds = new ArrayList<>(qtyByWine.keySet());
        wineIds.sort(Long::compareTo);

        BigDecimal total = BigDecimal.ZERO;
        List<SaleOutItem> persistedItems = new ArrayList<>();

        // 先建出窖单主记录（明细稍后补全）
        SaleOut sale = new SaleOut();
        sale.memberId = hasMember ? input.memberId : null;
        sale.total = BigDecimal.ZERO;
        sale.status = "已出窖";
        sale = saleRepository.save(sale);

        for (Long wineId : wineIds) {
            int qty = qtyByWine.get(wineId);
            Wine w = wineRepository.findWithLockById(wineId)
                    .orElseThrow(() -> new BizException("酒款不存在"));
            if (!"在售".equals(w.status)) {
                throw new BizException("酒款「" + w.name + "」已停售，不可下单");
            }
            List<AllocationLine> lines = inventoryService.deduct(w, qty);
            if (lines.isEmpty()) {
                throw new BizException("酒款「" + w.name + "」没有已放行（可售）的批次，不能出窖；请先放行窖藏批次");
            }
            total = total.add(w.price.multiply(BigDecimal.valueOf(qty)));

            // 3. 实际扣了哪些批次就逐行落明细，批次来源必须保留
            for (AllocationLine l : lines) {
                SaleOutItem item = new SaleOutItem();
                item.saleId = sale.id;
                item.wineId = wineId;
                item.batchId = l.batchId;
                item.qty = l.qty;
                persistedItems.add(item);
            }
        }

        sale.total = total;
        saleRepository.save(sale);
        itemRepository.saveAll(persistedItems);

        // 4. 关联会员累加积分（散客不累加）；退窖时对称扣回
        if (hasMember) {
            memberService.addPoints(sale.memberId, total.intValue());
        }
        return sale;
    }

    // ---------------- 退窖（仅已出窖可退，按原批次逐项回补） ----------------
    @Transactional
    public SaleOut refund(Long id) {
        // 先锁单据：并发的第二次退窖会被挡住，不会二次回补/二次扣积分
        SaleOut sale = saleRepository.findWithLockById(id)
                .orElseThrow(() -> new BizException("出窖单不存在"));
        if (!"已出窖".equals(sale.status)) {
            throw new BizException("仅「已出窖」的订单可退窖，当前为「" + sale.status + "」，请勿重复操作");
        }

        List<SaleOutItem> its = itemRepository.findBySaleId(id);
        if (its.isEmpty()) {
            throw new BizException("出窖单缺少批次明细，无法按原批次回补，已拦截");
        }

        // 按酒款分组（酒款内再按原批次合并），按酒款 id 升序加锁
        Map<Long, List<AllocationLine>> byWine = new LinkedHashMap<>();
        for (SaleOutItem it : its) {
            if (it.batchId == null) {
                throw new BizException("出窖明细缺少批次来源，无法按原批次回补，已拦截");
            }
            byWine.computeIfAbsent(it.wineId, k -> new ArrayList<>())
                    .add(new AllocationLine(it.batchId, null, it.qty));
        }
        List<Long> wineIds = new ArrayList<>(byWine.keySet());
        wineIds.sort(Long::compareTo);

        // 预检：会员当前积分需足以扣回本单累计值（1 元 = 1 分），避免回补完库存才因积分为负整体回滚
        if (sale.memberId != null) {
            int points = memberService.getPoints(sale.memberId);
            if (points < sale.total.intValue()) {
                throw new BizException("本单会员当前积分(" + points
                        + ") 不足以扣回本单积分(" + sale.total.intValue()
                        + ")，请先人工核对会员积分后再退窖");
            }
        }

        BigDecimal totalBack = BigDecimal.ZERO;
        for (Long wineId : wineIds) {
            Wine w = wineRepository.findWithLockById(wineId)
                    .orElseThrow(() -> new BizException("原出窖酒款不存在，无法退窖"));
            inventoryService.restore(w, byWine.get(wineId));
            totalBack = totalBack.add(w.price.multiply(
                    BigDecimal.valueOf(byWine.get(wineId).stream().mapToInt(l -> l.qty).sum())));
        }

        sale.status = "已退窖";
        saleRepository.save(sale);

        // 会员积分随最终状态：出窖加过多少，退窖对称扣回（散客本就无积分）
        if (sale.memberId != null) {
            memberService.addPoints(sale.memberId, -totalBack.intValue());
        }
        return sale;
    }
}
