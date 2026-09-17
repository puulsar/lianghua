package com.brotherc.aquant.sync.repository;

import com.brotherc.aquant.sync.entity.StockDataHealthCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockDataHealthCheckRepository extends JpaRepository<StockDataHealthCheck, Long> {

    List<StockDataHealthCheck> findTop20ByOrderByCheckTimeDesc();

}
