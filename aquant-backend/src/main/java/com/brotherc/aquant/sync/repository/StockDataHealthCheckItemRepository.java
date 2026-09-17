package com.brotherc.aquant.sync.repository;

import com.brotherc.aquant.sync.entity.StockDataHealthCheckItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockDataHealthCheckItemRepository extends JpaRepository<StockDataHealthCheckItem, Long> {

    List<StockDataHealthCheckItem> findByCheckIdOrderBySortOrderAsc(Long checkId);

}
