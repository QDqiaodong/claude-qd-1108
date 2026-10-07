package com.wine.cellar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wine.cellar.entity.CellarBatch;

import jakarta.persistence.LockModeType;

public interface CellarBatchRepository extends JpaRepository<CellarBatch, Long> {

    Optional<CellarBatch> findByBatchNo(String batchNo);

    boolean existsByBatchNo(String batchNo);

    boolean existsByBatchNoAndIdNot(String batchNo, Long id);

    List<CellarBatch> findByWineId(Long wineId);

    List<CellarBatch> findByStatus(String status);

    /** 只取批次所属酒款 id（投影，不把实体装入一级缓存，供放行加锁前定位酒款） */
    @Query("select b.wineId from CellarBatch b where b.id = :id")
    Optional<Long> findWineIdById(Long id);

    /** 某酒款指定状态批次可用瓶数合计（聚合，不加载实体；只读预检/展示用） */
    @Query("select coalesce(sum(b.availableBottles), 0) from CellarBatch b "
            + "where b.wineId = :wineId and b.status = :status")
    Long sumAvailableByWineIdAndStatus(@Param("wineId") Long wineId, @Param("status") String status);

    /** 行锁加载单个批次（放行/扣减/回补等状态流转使用，拒绝并发重复操作） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CellarBatch b where b.id = :id")
    Optional<CellarBatch> findWithLockById(Long id);

    /**
     * 行锁加载某酒款当前可售批次（当前读），按入窖日最早优先（同日按 id）——FEFO 扣减顺序。
     * 加锁保证并发出窖时同一批次不会被两个事务同时扣成负数；权威的库存一致性/充足性判断都基于此当前读结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from CellarBatch b
            where b.wineId = :wineId and b.status = :status
            order by b.cellarDate asc, b.id asc
            """)
    List<CellarBatch> findSellableWithLock(@Param("wineId") Long wineId, @Param("status") String status);
}
