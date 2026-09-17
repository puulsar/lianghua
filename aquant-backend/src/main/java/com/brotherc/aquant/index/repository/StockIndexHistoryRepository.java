package com.brotherc.aquant.index.repository;

import com.brotherc.aquant.index.entity.StockIndexHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockIndexHistoryRepository extends JpaRepository<StockIndexHistory, Long>, JpaSpecificationExecutor<StockIndexHistory> {

    Optional<StockIndexHistory> findByIndexCodeAndTradeDate(String indexCode, LocalDate tradeDate);

    Optional<StockIndexHistory> findFirstByIndexCodeOrderByTradeDateDesc(String indexCode);

    List<StockIndexHistory> findByIndexCodeOrderByTradeDateDesc(String indexCode, Pageable pageable);

    List<StockIndexHistory> findByIndexCodeOrderByTradeDateDesc(String indexCode);

    List<StockIndexHistory> findByIndexCodeOrderByTradeDateAsc(String indexCode);

    /** 统计某个交易日已落库的核心指数数量（用于收盘后体检） */
    @Query("select count(h) from StockIndexHistory h " +
            "where h.tradeDate = :tradeDate and h.indexCode in :codes")
    long countByTradeDateAndIndexCodeIn(@Param("tradeDate") LocalDate tradeDate,
                                        @Param("codes") Collection<String> codes);

}
