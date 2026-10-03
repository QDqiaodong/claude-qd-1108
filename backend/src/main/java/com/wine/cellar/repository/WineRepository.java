package com.wine.cellar.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wine.cellar.entity.Wine;

public interface WineRepository extends JpaRepository<Wine, Long> {

    Optional<Wine> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}
