package com.wine.cellar.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.wine.cellar.entity.SaleOut;

import jakarta.persistence.LockModeType;

public interface SaleOutRepository extends JpaRepository<SaleOut, Long> {

    java.util.List<SaleOut> findByStatus(String status);

    /** 行锁加载出窖单（退窖重复提交时第二次会被挡住，不会二次回补） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SaleOut s where s.id = :id")
    Optional<SaleOut> findWithLockById(Long id);
}
