package com.brotherc.aquant.strategy.repository;

import com.brotherc.aquant.strategy.entity.StockStrategyMomentumBacktestSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockStrategyMomentumBacktestSnapshotRepository extends
        JpaRepository<StockStrategyMomentumBacktestSnapshot, Long>,
        JpaSpecificationExecutor<StockStrategyMomentumBacktestSnapshot> {

    boolean existsByBatchNoAndMarketAndLookbackDaysAndRecentYears(
            Long batchNo, String market, Integer lookbackDays, Integer recentYears
    );

    long countByBatchNo(Long batchNo);

    @Query(value = "SELECT COALESCE(reliability, '未知') AS r, COUNT(*) AS c FROM stock_strategy_momentum_backtest_snapshot "
            + "WHERE batch_no = :batchNo GROUP BY reliability", nativeQuery = true)
    List<Object[]> reliabilityDistribution(@Param("batchNo") Long batchNo);

    @Query(value = "SELECT lookback_days, recent_years, COUNT(*) AS cnt, "
            + "AVG(total_return) AS avgRet, AVG(win_rate) AS avgWin "
            + "FROM stock_strategy_momentum_backtest_snapshot WHERE batch_no = :batchNo "
            + "GROUP BY lookback_days, recent_years ORDER BY cnt DESC LIMIT 6", nativeQuery = true)
    List<Object[]> topCombos(@Param("batchNo") Long batchNo);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM stock_strategy_momentum_backtest_snapshot WHERE batch_no <> :batchNo LIMIT :limit", nativeQuery = true)
    int deleteOldBatchLimit(@Param("batchNo") Long batchNo, @Param("limit") int limit);

}
