package com.wine.cellar.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.Member;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.SaleOutItem;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.SaleOutItemRepository;
import com.wine.cellar.repository.SaleOutRepository;
import com.wine.cellar.repository.WineRepository;

@Service
public class SaleOutService {

    private final SaleOutRepository saleRepository;
    private final SaleOutItemRepository itemRepository;
    private final WineRepository wineRepository;
    private final WineService wineService;
    private final MemberService memberService;

    public SaleOutService(SaleOutRepository saleRepository,
                          SaleOutItemRepository itemRepository,
                          WineRepository wineRepository,
                          WineService wineService,
                          MemberService memberService) {
        this.saleRepository = saleRepository;
        this.itemRepository = itemRepository;
        this.wineRepository = wineRepository;
        this.wineService = wineService;
        this.memberService = memberService;
    }

    // ---------------- 查询 ----------------
    public List<SaleOut> list(String status) {
        List<SaleOut> all = saleRepository.findAll();
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(s -> status.equals(s.status)).toList();
        }
        return all;
    }

    public SaleOut get(Long id) {
        return saleRepository.findById(id)
                .orElseThrow(() -> new BizException("出窖单不存在"));
    }

    public List<SaleOutItem> items(Long saleId) {
        return itemRepository.findBySaleId(saleId);
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

    // ---------------- 新建即出窖（扣库存） ----------------
    @Transactional
    public SaleOut create(SaleCreateInput input) {
        if (input.items == null || input.items.isEmpty()) {
            throw new BizException("出窖明细不能为空");
        }

        // 关联会员（散客可空）
        Member member = null;
        if (input.memberId != null) {
            member = memberService.get(input.memberId);
        }

        List<Wine> touched = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (SaleItemInput it : input.items) {
            if (it.wineId == null) {
                throw new BizException("出窖明细缺少酒款");
            }
            if (it.qty == null || it.qty <= 0) {
                throw new BizException("出窖数量必须大于 0");
            }
            Wine w = wineRepository.findById(it.wineId)
                    .orElseThrow(() -> new BizException("酒款不存在"));
            // 校验可下单（在售且库存足）并扣库存
            wineService.deductStock(w, it.qty);
            if (!touched.contains(w)) {
                touched.add(w);
            }
            total = total.add(w.price.multiply(BigDecimal.valueOf(it.qty)));
        }

        SaleOut sale = new SaleOut();
        sale.memberId = (member == null) ? null : member.id;
        sale.total = total;
        sale.status = "已出窖";
        SaleOut saved = saleRepository.save(sale);

        for (SaleItemInput it : input.items) {
            SaleOutItem item = new SaleOutItem();
            item.saleId = saved.id;
            item.wineId = it.wineId;
            item.qty = it.qty;
            itemRepository.save(item);
        }

        // 回写库存
        wineRepository.saveAll(touched);

        // 关联会员则累加积分（1 元 = 1 积分）
        if (member != null) {
            memberService.addPoints(member.id, total.intValue());
        }

        return saved;
    }

    // ---------------- 退窖（仅已出窖可退，回滚库存） ----------------
    @Transactional
    public SaleOut refund(Long id) {
        SaleOut sale = get(id);
        if (!Dict.SALE_OUT_STATUS.contains(sale.status) || !"已出窖".equals(sale.status)) {
            throw new BizException("仅「已出窖」的订单可退窖，当前为「" + sale.status + "」");
        }

        List<SaleOutItem> its = itemRepository.findBySaleId(id);
        for (SaleOutItem it : its) {
            Wine w = wineRepository.findById(it.wineId)
                    .orElseThrow(() -> new BizException("酒款不存在"));
            w.stock += it.qty;
            wineRepository.save(w);
        }

        sale.status = "已退窖";
        return saleRepository.save(sale);
    }
}
