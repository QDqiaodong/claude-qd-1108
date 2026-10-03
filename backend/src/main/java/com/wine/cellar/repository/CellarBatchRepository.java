package com.wine.cellar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wine.cellar.entity.CellarBatch;

public interface CellarBatchRepository extends JpaRepository<CellarBatch, Long> {

    Optional<CellarBatch> findByBatchNo(String batchNo);

    boolean existsByBatchNo(String batchNo);

    boolean existsByBatchNoAndIdNot(String batchNo, Long id);

    List<CellarBatch> findByWineId(Long wineId);

    List<CellarBatch> findByStatus(String status);
}
