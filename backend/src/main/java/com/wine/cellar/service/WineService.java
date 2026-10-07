package com.wine.cellar.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.WineRepository;

@Service
public class WineService {

    private final WineRepository wineRepository;

    public WineService(WineRepository wineRepository) {
        this.wineRepository = wineRepository;
    }

    // ---------------- 查询 ----------------
    public List<Wine> list(String region, String type, String status) {
        List<Wine> all = wineRepository.findAll();
        if (region != null && !region.isBlank()) {
            all = all.stream().filter(w -> region.equals(w.region)).toList();
        }
        if (type != null && !type.isBlank()) {
            all = all.stream().filter(w -> type.equals(w.type)).toList();
        }
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(w -> status.equals(w.status)).toList();
        }
        return all;
    }

    public Wine get(Long id) {
        return wineRepository.findById(id)
                .orElseThrow(() -> new BizException("酒款不存在"));
    }

    // ---------------- 新建 ----------------
    public static class WineInput {
        public String code;
        public String name;
        public String region;
        public String type;
        public Integer stock;
        public BigDecimal price;
        public String status;
    }

    public Wine create(WineInput input) {
        if (input.code == null || input.code.isBlank()) {
            throw new BizException("酒款编号不能为空");
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("酒名不能为空");
        }
        if (!Dict.isValidWineRegion(input.region)) {
            throw new BizException("产区必须是：国产/法国/智利/澳洲/其他 之一");
        }
        if (!Dict.isValidWineType(input.type)) {
            throw new BizException("类型必须是：红/白/起泡/甜 之一");
        }
        if (input.stock != null && input.stock != 0) {
            throw new BizException("新酒款可售库存必须为 0：请先在「窖藏批次」新建批次并放行，库存由批次联动产生");
        }
        if (input.price == null || input.price.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("单价不能为负");
        }
        if (wineRepository.existsByCode(input.code)) {
            throw new BizException("酒款编号已存在，不可重复");
        }

        Wine w = new Wine();
        w.code = input.code;
        w.name = input.name;
        w.region = input.region;
        w.type = input.type;
        w.stock = 0;   // 库存只能来自放行批次
        w.price = input.price;
        w.status = (input.status == null || input.status.isBlank()) ? "在售" : input.status;
        if (!Dict.isValidWineStatus(w.status)) {
            throw new BizException("状态必须是：在售/停售 之一");
        }
        return wineRepository.save(w);
    }

    // ---------------- 更新（部分字段） ----------------
    public Wine update(Long id, WineInput input) {
        Wine w = get(id);
        if (input.code != null) {
            if (input.code.isBlank()) {
                throw new BizException("酒款编号不能为空");
            }
            if (!input.code.equals(w.code) && wineRepository.existsByCodeAndIdNot(input.code, id)) {
                throw new BizException("酒款编号已存在，不可重复");
            }
            w.code = input.code;
        }
        if (input.name != null) {
            if (input.name.isBlank()) {
                throw new BizException("酒名不能为空");
            }
            w.name = input.name;
        }
        if (input.region != null) {
            if (!Dict.isValidWineRegion(input.region)) {
                throw new BizException("产区必须是：国产/法国/智利/澳洲/其他 之一");
            }
            w.region = input.region;
        }
        if (input.type != null) {
            if (!Dict.isValidWineType(input.type)) {
                throw new BizException("类型必须是：红/白/起泡/甜 之一");
            }
            w.type = input.type;
        }
        if (input.stock != null && input.stock != w.stock) {
            throw new BizException("可售库存由窖藏批次联动（放行增加、出窖扣减、退窖回补），不可直接修改");
        }
        if (input.price != null) {
            if (input.price.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("单价不能为负");
            }
            w.price = input.price;
        }
        if (input.status != null) {
            if (!Dict.isValidWineStatus(input.status)) {
                throw new BizException("状态必须是：在售/停售 之一");
            }
            w.status = input.status;
        }
        return wineRepository.save(w);
    }
}
