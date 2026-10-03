package com.wine.cellar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wine.cellar.entity.SaleOut;

public interface SaleOutRepository extends JpaRepository<SaleOut, Long> {

    List<SaleOut> findByStatus(String status);
}
