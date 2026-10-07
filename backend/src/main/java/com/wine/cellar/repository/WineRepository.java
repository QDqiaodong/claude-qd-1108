package com.wine.cellar.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.wine.cellar.entity.Wine;

import jakarta.persistence.LockModeType;

public interface WineRepository extends JpaRepository<Wine, Long> {

    Optional<Wine> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    /** 行锁加载酒款（出窖扣减/退窖回补/放行联动库存使用） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wine w where w.id = :id")
    Optional<Wine> findWithLockById(Long id);
}
