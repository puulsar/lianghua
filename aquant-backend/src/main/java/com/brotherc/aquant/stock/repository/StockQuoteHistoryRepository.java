package com.brotherc.aquant.stock.repository;

import com.brotherc.aquant.stock.entity.StockQuoteHistory;
import com.brotherc.aquant.stock.model.dto.StockQuoteHistoryProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StockQuoteHistoryRepository extends JpaRepository<StockQuoteHistory, Long> {

        List<StockQuoteHistory> findByTradeDateAndCodeIn(String tradeDate, List<String> codeList);

        List<StockQuoteHistory> findByCodeAndTradeDateIn(String code, List<String> tradeDateList);

        List<StockQuoteHistory> findByCodeOrderByTradeDateAsc(String code);

        @Query(value = "SELECT * " +
                        "FROM stock_quote_history " +
                        "WHERE code = :code " +
                        "ORDER BY trade_date DESC " +
                        "LIMIT :limit", nativeQuery = true)
        List<StockQuoteHistory> findLatestByCode(@Param("code") String code, @Param("limit") int limit);

    @Query(value = "SELECT DISTINCT trade_date FROM stock_quote_history ORDER BY trade_date DESC LIMIT :limit", nativeQuery = true)
    List<String> findRecentTradeDates(@Param("limit") int limit);

    @Query(value = "SELECT DISTINCT trade_date FROM stock_quote_history " +
            "WHERE trade_date <= :tradeDate ORDER BY trade_date DESC LIMIT :limit", nativeQuery = true)
    List<String> findRecentTradeDatesBefore(@Param("tradeDate") String tradeDate, @Param("limit") int limit);

    List<StockQuoteHistoryProjection> findByTradeDateInAndCodeInOrderByTradeDateAsc(List<String> tradeDates, List<String> codeList);

    @Query("select max(s.tradeDate) from StockQuoteHistory s")
    String findMaxTradeDate();

    /**
     * 按交易日汇总全市场成交额（各股 turnover 之和，单位：元）。
     * 用作「近5日成交额」的兜底数据源：板块历史（stock_industry_board_history）盘后回补
     * 会受上游同花顺发布时间影响出现单日缺口，缺口日回退到这里，避免图表静默少一天。
     */
    @Query(value = "SELECT trade_date, SUM(turnover) FROM stock_quote_history " +
            "WHERE trade_date IN (:dates) GROUP BY trade_date", nativeQuery = true)
    List<Object[]> sumTurnoverByTradeDates(@Param("dates") Collection<String> dates);

    @Query("select s.code, max(s.tradeDate) from StockQuoteHistory s " +
            "where s.code in :codes and s.tradeDate <= :endDate group by s.code")
    List<Object[]> findMaxTradeDateByCodeInBeforeOrEqual(@Param("codes") List<String> codes,
                                                         @Param("endDate") String endDate);

    long deleteByCodeIn(List<String> codeList);

    /** 统计某个交易日已落库日 K 线的股票数量（用于收盘后体检：当日覆盖率） */
    @Query(value = "SELECT COUNT(DISTINCT code) FROM stock_quote_history WHERE trade_date = :tradeDate", nativeQuery = true)
    long countDistinctCodeByTradeDate(@Param("tradeDate") String tradeDate);

    /**
     * 统计某个交易日的「假 K 线」数量：开/高/低/成交量任一为 0 或 NULL。
     * <p>
     * 停牌股的实时接口会返回全 0 占位行，若被当成日 K 落库就是一根从 0 拉到昨收的假 K 线，
     * 会污染所有按日 K 计算的指标。收盘后体检必须把它当硬错误查出来。
     */
    @Query(value = "SELECT COUNT(*) FROM stock_quote_history WHERE trade_date = :tradeDate " +
            "AND (COALESCE(open_price, 0) <= 0 OR COALESCE(high_price, 0) <= 0 " +
            "OR COALESCE(low_price, 0) <= 0 OR COALESCE(volume, 0) <= 0)", nativeQuery = true)
    long countInvalidQuoteByTradeDate(@Param("tradeDate") String tradeDate);

}
