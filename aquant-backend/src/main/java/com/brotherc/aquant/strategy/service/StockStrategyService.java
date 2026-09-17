package com.brotherc.aquant.strategy.service;

import com.brotherc.aquant.stock.entity.StockQuote;
import com.brotherc.aquant.common.exception.BusinessException;
import com.brotherc.aquant.common.exception.ExceptionEnum;
import com.brotherc.aquant.strategy.model.vo.DualMAReqVO;
import com.brotherc.aquant.strategy.model.vo.DualMABacktestReqVO;
import com.brotherc.aquant.strategy.model.vo.MomentumReqVO;
import com.brotherc.aquant.strategy.model.vo.MomentumBacktestReqVO;
import com.brotherc.aquant.strategy.model.vo.MacdReqVO;
import com.brotherc.aquant.strategy.model.vo.MacdBacktestReqVO;
import com.brotherc.aquant.strategy.model.vo.GridReqVO;
import com.brotherc.aquant.strategy.model.vo.GridBacktestReqVO;
import com.brotherc.aquant.strategy.model.vo.StockTradeSignalVO;
import com.brotherc.aquant.strategy.model.vo.StockTradeBacktestVO;
import com.brotherc.aquant.strategy.support.StrategyReliability;
import com.brotherc.aquant.stock.repository.StockQuoteRepository;
import com.brotherc.aquant.watchlist.repository.StockWatchlistGroupRepository;
import com.brotherc.aquant.watchlist.repository.StockWatchlistStockRepository;
import com.brotherc.aquant.watchlist.entity.StockWatchlistStock;
import com.brotherc.aquant.common.utils.UserContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockStrategyService {

    private static final String SIGNAL = "signal";
    private static final String LATEST_PRICE = "latestPrice";

    /**
     * 全市场回测（非预设参数）代价很高：需要遍历全部股票的历史行情。
     * 这里做两级保护：
     * 1) 结果缓存（短期 + LRU），翻页/排序/重复查询不再重复计算；
     * 2) 并发许可，避免多个全市场回测同时把堆吃满导致 OOM。
     */
    private static final long BACKTEST_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int BACKTEST_CACHE_MAX_ENTRIES = 6;
    private static final int BACKTEST_MAX_CONCURRENCY = 2;
    private static final long BACKTEST_PERMIT_WAIT_MS = TimeUnit.SECONDS.toMillis(180);

    private final Semaphore backtestPermits = new Semaphore(BACKTEST_MAX_CONCURRENCY, true);
    private final Map<String, BacktestCacheEntry> backtestCache = new LinkedHashMap<>(16, 0.75f, true);

    private final DualMovingAverageStrategy dualMovingAverageStrategy;
    private final MomentumStrategy momentumStrategy;
    private final MacdStrategy macdStrategy;
    private final GridTradingStrategy gridTradingStrategy;
    private final StockWatchlistStockRepository stockWatchlistStockRepository;
    private final StockQuoteRepository stockQuoteRepository;
    private final StockWatchlistGroupRepository stockWatchlistGroupRepository;
    private final StockStrategySnapshotService stockStrategySnapshotService;

    public Page<StockTradeSignalVO> dualMA(DualMAReqVO reqVO, Pageable pageable) {
        Set<String> watchlistCodes = null;
        if (reqVO.getWatchlistGroupId() != null) {
            Long userId = UserContext.requireCurrentUserId();
            stockWatchlistGroupRepository.findByIdAndUserId(reqVO.getWatchlistGroupId(), userId)
                    .orElseThrow(() -> new BusinessException(ExceptionEnum.WATCHLIST_GROUP_NOT_FOUND));
            watchlistCodes = stockWatchlistStockRepository
                    .findByGroupIdOrderBySortNoDesc(reqVO.getWatchlistGroupId())
                    .stream().map(StockWatchlistStock::getStockCode).collect(Collectors.toSet());
            if (CollectionUtils.isEmpty(watchlistCodes)) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
        }

        if (StringUtils.isBlank(reqVO.getSignal())) {
            Specification<StockQuote> stockQuoteSpecification = buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket());
            Page<StockQuote> pagedStocks = stockQuoteRepository.findAll(stockQuoteSpecification, pageable);
            if (pagedStocks.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
            List<StockTradeSignalVO> pagedList = dualMovingAverageStrategy.calculate(
                    reqVO.getMaShort(), reqVO.getMaLong(), pagedStocks.getContent()
            );
            return new PageImpl<>(pagedList, pageable, pagedStocks.getTotalElements());
        }

        List<StockQuote> allStocks = stockQuoteRepository.findAll();
        Stream<StockQuote> quoteStream = allStocks.stream();

        if (StringUtils.isNotBlank(reqVO.getMarket())) {
            final String market = reqVO.getMarket().toLowerCase();
            quoteStream = quoteStream.filter(vo -> vo.getCode() != null && vo.getCode().toLowerCase().startsWith(market));
        }

        if (StringUtils.isNotBlank(reqVO.getCode())) {
            quoteStream = quoteStream.filter(vo -> reqVO.getCode().equalsIgnoreCase(vo.getCode()));
        }

        if (watchlistCodes != null) {
            final Set<String> wc = watchlistCodes;
            quoteStream = quoteStream.filter(vo -> {
                String c = vo.getCode();
                String c6 = c.length() > 6 ? c.substring(c.length() - 6) : c;
                return wc.contains(c6);
            });
        }

        List<StockQuote> targetStocks = quoteStream.toList();
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        List<StockTradeSignalVO> list = dualMovingAverageStrategy.calculate(reqVO.getMaShort(), reqVO.getMaLong(), targetStocks);

        if (StringUtils.isNotBlank(reqVO.getSignal())) {
            list = list.stream().filter(vo -> reqVO.getSignal().equalsIgnoreCase(vo.getSignal())).collect(Collectors.toList());
        }

        Sort sort = pageable.getSort();
        if (sort.isSorted()) {
            list = new ArrayList<>(list);
            list.sort(buildComparator(sort));
        }

        int total = list.size();
        int pageSize = pageable.getPageSize();
        int currentPage = pageable.getPageNumber();
        int fromIndex = currentPage * pageSize;

        if (fromIndex >= total) {
            return new PageImpl<>(Collections.emptyList(), pageable, total);
        }

        int toIndex = Math.min(fromIndex + pageSize, total);
        return new PageImpl<>(list.subList(fromIndex, toIndex), pageable, total);
    }

    private Comparator<StockTradeSignalVO> buildComparator(Sort sort) {
        Comparator<StockTradeSignalVO> result = null;

        for (Sort.Order order : sort) {
            Comparator<StockTradeSignalVO> comparator = null;
            if ("code".equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getCode);
            } else if ("name".equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getName);
            } else if (SIGNAL.equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getSignal);
            } else if ("pir".equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeSignalVO::getPir,
                        Comparator.nullsLast(BigDecimal::compareTo));
            } else if (LATEST_PRICE.equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getLatestPrice);
            }

            if (comparator == null) {
                continue;
            }

            if (order.getDirection() == Sort.Direction.DESC) {
                comparator = comparator.reversed();
            }

            result = (result == null) ? comparator : result.thenComparing(comparator);
        }

        return result != null ? result : Comparator.comparing(StockTradeSignalVO::getCode);
    }

    public Page<StockTradeBacktestVO> dualMABacktest(DualMABacktestReqVO reqVO, Pageable pageable) {
        java.util.Set<String> watchlistCodes = null;
        if (reqVO.getWatchlistGroupId() != null) {
            Long userId = UserContext.requireCurrentUserId();
            stockWatchlistGroupRepository.findByIdAndUserId(reqVO.getWatchlistGroupId(), userId)
                    .orElseThrow(() -> new BusinessException(ExceptionEnum.WATCHLIST_GROUP_NOT_FOUND));
            watchlistCodes = stockWatchlistStockRepository
                    .findByGroupIdOrderBySortNoDesc(reqVO.getWatchlistGroupId())
                    .stream().map(StockWatchlistStock::getStockCode).collect(Collectors.toSet());
            if (CollectionUtils.isEmpty(watchlistCodes)) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
        }

        Page<StockTradeBacktestVO> snapshotPage = stockStrategySnapshotService
                .queryDualMABacktestSnapshot(reqVO, pageable, watchlistCodes);
        if (snapshotPage != null) {
            return postProcessBacktest(snapshotPage.getContent(), pageable, reqVO.getReliability());
        }

        return dualMABacktestOnline(reqVO, pageable, watchlistCodes);
    }

    private Page<StockTradeBacktestVO> dualMABacktestOnline(
            DualMABacktestReqVO reqVO,
            Pageable pageable,
            Set<String> watchlistCodes
    ) {
        List<StockQuote> targetStocks = stockQuoteRepository.findAll(
                buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket())
        );
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        List<StockTradeBacktestVO> result = computeFullBacktest(
                backtestCacheKey("dualma", reqVO.getMarket(), reqVO.getCode(), reqVO.getWatchlistGroupId(),
                        reqVO.getMaShort(), reqVO.getMaLong(), reqVO.getRecentYears()),
                () -> dualMovingAverageStrategy.backtest(
                        reqVO.getMaShort(), reqVO.getMaLong(), reqVO.getRecentYears(), targetStocks));

        StrategyReliability.applyFdr(result);

        if (StringUtils.isNotBlank(reqVO.getReliability())) {
            result = result.stream()
                    .filter(vo -> reqVO.getReliability().equals(vo.getReliability()))
                    .collect(Collectors.toList());
        }

        Sort sort = pageable.getSort();
        if (sort.isSorted()) {
            result = new ArrayList<>(result);
            result.sort(buildBacktestComparator(sort));
        }

        int total = result.size();
        int pageSize = pageable.getPageSize();
        int currentPage = pageable.getPageNumber();
        int fromIndex = currentPage * pageSize;

        if (fromIndex >= total) {
            return new PageImpl<>(Collections.emptyList(), pageable, total);
        }

        int toIndex = Math.min(fromIndex + pageSize, total);
        return new PageImpl<>(result.subList(fromIndex, toIndex), pageable, total);
    }

    private Comparator<StockTradeBacktestVO> buildBacktestComparator(Sort sort) {
        Comparator<StockTradeBacktestVO> result = null;

        for (Sort.Order order : sort) {
            Comparator<StockTradeBacktestVO> comparator = null;
            boolean reverse = order.getDirection() == Sort.Direction.DESC;
            if ("code".equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeBacktestVO::getCode);
            } else if ("name".equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeBacktestVO::getName);
            } else if ("totalReturn".equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeBacktestVO::getTotalReturn,
                        Comparator.nullsLast(BigDecimal::compareTo));
            } else if ("tradeCount".equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeBacktestVO::getTradeCount,
                        Comparator.nullsLast(Integer::compareTo));
            } else if ("winRate".equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeBacktestVO::getWinRate,
                        Comparator.nullsLast(BigDecimal::compareTo));
            } else if ("pValue".equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeBacktestVO::getPValue,
                        reverse ? Comparator.nullsLast(Comparator.reverseOrder()) : Comparator.nullsLast(Double::compareTo));
                reverse = false;
            } else if (LATEST_PRICE.equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeBacktestVO::getLatestPrice,
                        Comparator.nullsLast(BigDecimal::compareTo));
            }

            if (comparator == null) {
                continue;
            }

            if (reverse) {
                comparator = comparator.reversed();
            }

            result = (result == null) ? comparator : result.thenComparing(comparator);
        }

        return result != null ? result : Comparator.comparing(StockTradeBacktestVO::getCode);
    }

    /**
     * 对完整回测集合统一做 FDR 校正、可靠度过滤、排序与分页。
     *
     * <p>快照路径与在线路径共用此方法：快照查询已返回全量集合（不做可靠度过滤），
     * 因此 FDR 能基于完整检验家族计算；在线路径同样传入完整集合。
     */
    private Page<StockTradeBacktestVO> postProcessBacktest(
            List<StockTradeBacktestVO> base, Pageable pageable, String reliability) {
        StrategyReliability.applyFdr(base);
        if (StringUtils.isNotBlank(reliability)) {
            base = base.stream()
                    .filter(vo -> reliability.equals(vo.getReliability()))
                    .collect(Collectors.toList());
        }
        Sort sort = pageable.getSort();
        if (sort.isSorted()) {
            base = new ArrayList<>(base);
            base.sort(buildBacktestComparator(sort));
        }
        int total = base.size();
        int pageSize = pageable.getPageSize();
        int currentPage = pageable.getPageNumber();
        int fromIndex = currentPage * pageSize;
        if (fromIndex >= total) {
            return new PageImpl<>(Collections.emptyList(), pageable, total);
        }
        int toIndex = Math.min(fromIndex + pageSize, total);
        return new PageImpl<>(base.subList(fromIndex, toIndex), pageable, total);
    }

    // ==================== 动量策略 ====================

    public Page<StockTradeSignalVO> momentum(MomentumReqVO reqVO, Pageable pageable) {
        java.util.Set<String> watchlistCodes = null;
        if (reqVO.getWatchlistGroupId() != null) {
            Long userId = UserContext.requireCurrentUserId();
            stockWatchlistGroupRepository.findByIdAndUserId(reqVO.getWatchlistGroupId(), userId)
                    .orElseThrow(() -> new BusinessException(ExceptionEnum.WATCHLIST_GROUP_NOT_FOUND));
            watchlistCodes = stockWatchlistStockRepository
                    .findByGroupIdOrderBySortNoDesc(reqVO.getWatchlistGroupId())
                    .stream().map(StockWatchlistStock::getStockCode).collect(Collectors.toSet());
            if (CollectionUtils.isEmpty(watchlistCodes)) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
        }

        boolean earlyPaginate = StringUtils.isBlank(reqVO.getSignal()) && !hasStrategySortFields(pageable.getSort());
        if (earlyPaginate) {
            Page<StockQuote> pagedStocks = stockQuoteRepository.findAll(buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket()), pageable);
            if (pagedStocks.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
            List<StockTradeSignalVO> pagedList = momentumStrategy.calculate(reqVO.getLookbackDays(), reqVO.getThreshold(), pagedStocks.getContent());
            return new PageImpl<>(pagedList, pageable, pagedStocks.getTotalElements());
        }

        List<StockQuote> allStocks = stockQuoteRepository.findAll();
        Stream<StockQuote> quoteStream = allStocks.stream();

        if (StringUtils.isNotBlank(reqVO.getMarket())) {
            final String market = reqVO.getMarket().toLowerCase();
            quoteStream = quoteStream.filter(vo -> vo.getCode() != null && vo.getCode().toLowerCase().startsWith(market));
        }

        if (StringUtils.isNotBlank(reqVO.getCode())) {
            quoteStream = quoteStream.filter(vo -> reqVO.getCode().equalsIgnoreCase(vo.getCode()));
        }

        if (watchlistCodes != null) {
            final java.util.Set<String> wc = watchlistCodes;
            quoteStream = quoteStream.filter(vo -> {
                String c = vo.getCode();
                String c6 = c.length() > 6 ? c.substring(c.length() - 6) : c;
                return wc.contains(c6);
            });
        }

        List<StockQuote> targetStocks = quoteStream.toList();
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        List<StockTradeSignalVO> list = momentumStrategy.calculate(reqVO.getLookbackDays(), reqVO.getThreshold(), targetStocks);

        if (StringUtils.isNotBlank(reqVO.getSignal())) {
            list = list.stream().filter(vo -> reqVO.getSignal().equalsIgnoreCase(vo.getSignal())).toList();
        }

        Sort sort = pageable.getSort();
        if (sort.isSorted()) {
            list = new java.util.ArrayList<>(list);
            list.sort(buildMomentumSignalComparator(sort));
        }

        int total = list.size();
        int pageSize = pageable.getPageSize();
        int currentPage = pageable.getPageNumber();
        int fromIndex = currentPage * pageSize;

        if (fromIndex >= total) {
            return new PageImpl<>(Collections.emptyList(), pageable, total);
        }

        int toIndex = Math.min(fromIndex + pageSize, total);
        return new PageImpl<>(list.subList(fromIndex, toIndex), pageable, total);
    }

    private Comparator<StockTradeSignalVO> buildMomentumSignalComparator(Sort sort) {
        Comparator<StockTradeSignalVO> result = null;

        for (Sort.Order order : sort) {
            Comparator<StockTradeSignalVO> comparator = null;
            if ("code".equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getCode);
            } else if ("name".equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getName);
            } else if (SIGNAL.equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getSignal);
            } else if ("pir".equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeSignalVO::getPir,
                        Comparator.nullsLast(BigDecimal::compareTo));
            } else if (LATEST_PRICE.equals(order.getProperty())) {
                comparator = Comparator.comparing(StockTradeSignalVO::getLatestPrice);
            } else if ("momentumValue".equals(order.getProperty())) {
                comparator = Comparator.comparing(
                        StockTradeSignalVO::getMomentumValue,
                        Comparator.nullsLast(BigDecimal::compareTo));
            }

            if (comparator == null) continue;
            if (order.getDirection() == Sort.Direction.DESC) comparator = comparator.reversed();
            result = (result == null) ? comparator : result.thenComparing(comparator);
        }

        return result != null ? result : Comparator.comparing(StockTradeSignalVO::getCode);
    }

    public Page<StockTradeBacktestVO> momentumBacktest(MomentumBacktestReqVO reqVO, Pageable pageable) {
        Set<String> watchlistCodes = null;
        if (reqVO.getWatchlistGroupId() != null) {
            Long userId = UserContext.requireCurrentUserId();
            stockWatchlistGroupRepository.findByIdAndUserId(reqVO.getWatchlistGroupId(), userId)
                    .orElseThrow(() -> new BusinessException(ExceptionEnum.WATCHLIST_GROUP_NOT_FOUND));
            watchlistCodes = stockWatchlistStockRepository
                    .findByGroupIdOrderBySortNoDesc(reqVO.getWatchlistGroupId())
                    .stream().map(StockWatchlistStock::getStockCode).collect(Collectors.toSet());
            if (CollectionUtils.isEmpty(watchlistCodes)) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
        }

        Page<StockTradeBacktestVO> snapshotPage = stockStrategySnapshotService
                .queryMomentumBacktestSnapshot(reqVO, pageable, watchlistCodes);
        if (snapshotPage != null) {
            return postProcessBacktest(snapshotPage.getContent(), pageable, reqVO.getReliability());
        }

        List<StockQuote> stocks = stockQuoteRepository.findAll();
        Stream<StockQuote> stream = stocks.stream();

        if (StringUtils.isNotBlank(reqVO.getMarket())) {
            final String market = reqVO.getMarket().toLowerCase();
            stream = stream.filter(vo -> vo.getCode() != null && vo.getCode().toLowerCase().startsWith(market));
        }

        if (StringUtils.isNotBlank(reqVO.getCode())) {
            stream = stream.filter(vo -> reqVO.getCode().equalsIgnoreCase(vo.getCode()));
        }

        if (watchlistCodes != null) {
            final java.util.Set<String> wc = watchlistCodes;
            stream = stream.filter(vo -> {
                String c = vo.getCode();
                String c6 = c.length() > 6 ? c.substring(c.length() - 6) : c;
                return wc.contains(c6);
            });
        }

        List<StockQuote> targetStocks = stream.toList();
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        List<StockTradeBacktestVO> result = computeFullBacktest(
                backtestCacheKey("momentum", reqVO.getMarket(), reqVO.getCode(), reqVO.getWatchlistGroupId(),
                        reqVO.getLookbackDays(), reqVO.getRecentYears()),
                () -> momentumStrategy.backtest(reqVO.getLookbackDays(), reqVO.getRecentYears(), targetStocks));

        StrategyReliability.applyFdr(result);

        if (StringUtils.isNotBlank(reqVO.getReliability())) {
            result = result.stream()
                    .filter(vo -> reqVO.getReliability().equals(vo.getReliability()))
                    .collect(Collectors.toList());
        }

        Sort sort = pageable.getSort();
        if (sort.isSorted()) {
            result = new ArrayList<>(result);
            result.sort(buildBacktestComparator(sort));
        }

        int total = result.size();
        int pageSize = pageable.getPageSize();
        int currentPage = pageable.getPageNumber();
        int fromIndex = currentPage * pageSize;

        if (fromIndex >= total) {
            return new PageImpl<>(Collections.emptyList(), pageable, total);
        }

        int toIndex = Math.min(fromIndex + pageSize, total);
        return new PageImpl<>(result.subList(fromIndex, toIndex), pageable, total);
    }

    // ==================== MACD策略 ====================

    public Page<StockTradeSignalVO> macd(MacdReqVO reqVO, Pageable pageable) {
        Set<String> watchlistCodes = loadWatchlistCodes(reqVO.getWatchlistGroupId());
        if (watchlistCodes != null && watchlistCodes.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        boolean earlyPaginate = StringUtils.isBlank(reqVO.getSignal()) && !hasStrategySortFields(pageable.getSort());
        if (earlyPaginate) {
            Page<StockQuote> pagedStocks = stockQuoteRepository.findAll(
                    buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket()), pageable
            );
            if (pagedStocks.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
            List<StockTradeSignalVO> pagedList = macdStrategy.calculate(
                    reqVO.getFastPeriod(), reqVO.getSlowPeriod(), reqVO.getSignalPeriod(), pagedStocks.getContent()
            );
            return new PageImpl<>(pagedList, pageable, pagedStocks.getTotalElements());
        }

        List<StockQuote> targetStocks = stockQuoteRepository.findAll(
                buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket())
        );
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        List<StockTradeSignalVO> result = macdStrategy.calculate(
                reqVO.getFastPeriod(), reqVO.getSlowPeriod(), reqVO.getSignalPeriod(), targetStocks
        );
        if (StringUtils.isNotBlank(reqVO.getSignal())) {
            result = result.stream()
                    .filter(item -> reqVO.getSignal().equalsIgnoreCase(item.getSignal()))
                    .toList();
        }
        if (pageable.getSort().isSorted()) {
            result = new ArrayList<>(result);
            result.sort(buildMacdSignalComparator(pageable.getSort()));
        }
        return toPage(result, pageable);
    }

    public Page<StockTradeBacktestVO> macdBacktest(MacdBacktestReqVO reqVO, Pageable pageable) {
        Set<String> watchlistCodes = loadWatchlistCodes(reqVO.getWatchlistGroupId());
        if (watchlistCodes != null && watchlistCodes.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        Page<StockTradeBacktestVO> snapshotPage = stockStrategySnapshotService
                .queryMacdBacktestSnapshot(reqVO, pageable, watchlistCodes);
        if (snapshotPage != null) {
            return postProcessBacktest(snapshotPage.getContent(), pageable, reqVO.getReliability());
        }

        List<StockQuote> targetStocks = stockQuoteRepository.findAll(
                buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket())
        );
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
        List<StockTradeBacktestVO> result = computeFullBacktest(
                backtestCacheKey("macd", reqVO.getMarket(), reqVO.getCode(), reqVO.getWatchlistGroupId(),
                        reqVO.getFastPeriod(), reqVO.getSlowPeriod(), reqVO.getSignalPeriod(), reqVO.getRecentYears()),
                () -> macdStrategy.backtest(
                        reqVO.getFastPeriod(), reqVO.getSlowPeriod(), reqVO.getSignalPeriod(),
                        reqVO.getRecentYears(), targetStocks
                ));
        StrategyReliability.applyFdr(result);
        if (StringUtils.isNotBlank(reqVO.getReliability())) {
            result = result.stream()
                    .filter(item -> reqVO.getReliability().equals(item.getReliability()))
                    .toList();
        }
        if (pageable.getSort().isSorted()) {
            result = new ArrayList<>(result);
            result.sort(buildBacktestComparator(pageable.getSort()));
        }
        return toPage(result, pageable);
    }

    // ==================== 网格交易策略 ====================

    public Page<StockTradeSignalVO> grid(GridReqVO reqVO, Pageable pageable) {
        Set<String> watchlistCodes = loadWatchlistCodes(reqVO.getWatchlistGroupId());
        if (watchlistCodes != null && watchlistCodes.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        boolean earlyPaginate = StringUtils.isBlank(reqVO.getSignal()) && !hasStrategySortFields(pageable.getSort());
        if (earlyPaginate) {
            Page<StockQuote> pagedStocks = stockQuoteRepository.findAll(
                    buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket()), pageable
            );
            if (pagedStocks.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
            List<StockTradeSignalVO> pagedList = gridTradingStrategy.calculate(
                    reqVO.getGridRate(), reqVO.getGridCount(), pagedStocks.getContent()
            );
            return new PageImpl<>(pagedList, pageable, pagedStocks.getTotalElements());
        }

        List<StockQuote> targetStocks = stockQuoteRepository.findAll(
                buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket())
        );
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
        List<StockTradeSignalVO> result = gridTradingStrategy.calculate(
                reqVO.getGridRate(), reqVO.getGridCount(), targetStocks
        );
        if (StringUtils.isNotBlank(reqVO.getSignal())) {
            result = result.stream()
                    .filter(item -> reqVO.getSignal().equalsIgnoreCase(item.getSignal()))
                    .toList();
        }
        if (pageable.getSort().isSorted()) {
            result = new ArrayList<>(result);
            result.sort(buildGridSignalComparator(pageable.getSort()));
        }
        return toPage(result, pageable);
    }

    public Page<StockTradeBacktestVO> gridBacktest(GridBacktestReqVO reqVO, Pageable pageable) {
        Set<String> watchlistCodes = loadWatchlistCodes(reqVO.getWatchlistGroupId());
        if (watchlistCodes != null && watchlistCodes.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        Page<StockTradeBacktestVO> snapshotPage = stockStrategySnapshotService
                .queryGridBacktestSnapshot(reqVO, pageable, watchlistCodes);
        if (snapshotPage != null) {
            return postProcessBacktest(snapshotPage.getContent(), pageable, reqVO.getReliability());
        }

        List<StockQuote> targetStocks = stockQuoteRepository.findAll(
                buildStockQuoteSpec(reqVO.getCode(), watchlistCodes, reqVO.getMarket())
        );
        if (targetStocks.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
        List<StockTradeBacktestVO> result = computeFullBacktest(
                backtestCacheKey("grid", reqVO.getMarket(), reqVO.getCode(), reqVO.getWatchlistGroupId(),
                        reqVO.getGridRate(), reqVO.getGridCount(), reqVO.getRecentYears()),
                () -> gridTradingStrategy.backtest(
                        reqVO.getGridRate(), reqVO.getGridCount(), reqVO.getRecentYears(), targetStocks
                ));
        // 必须对完整检验集合做多重检验校正，否则上千只股票同时检验会产生大量假阳性
        StrategyReliability.applyFdr(result);
        if (StringUtils.isNotBlank(reqVO.getReliability())) {
            result = result.stream()
                    .filter(item -> reqVO.getReliability().equals(item.getReliability()))
                    .toList();
        }
        if (pageable.getSort().isSorted()) {
            result = new ArrayList<>(result);
            result.sort(buildBacktestComparator(pageable.getSort()));
        }
        return toPage(result, pageable);
    }

    /**
     * 带缓存与并发限制的全市场回测计算。
     * 返回的列表只允许读取：上层会先复制再排序，不会修改缓存数据。
     */
    private List<StockTradeBacktestVO> computeFullBacktest(
            String cacheKey,
            Supplier<List<StockTradeBacktestVO>> supplier
    ) {
        long now = System.currentTimeMillis();
        synchronized (backtestCache) {
            BacktestCacheEntry cached = backtestCache.get(cacheKey);
            if (cached != null && cached.expireAt > now) {
                return cached.data;
            }
        }

        boolean acquired = false;
        try {
            acquired = backtestPermits.tryAcquire(BACKTEST_PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(ExceptionEnum.STOCK_STRATEGY_CALC_BUSY);
            }
            synchronized (backtestCache) {
                BacktestCacheEntry cached = backtestCache.get(cacheKey);
                if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                    return cached.data;
                }
            }

            List<StockTradeBacktestVO> data = supplier.get();

            synchronized (backtestCache) {
                backtestCache.put(
                        cacheKey,
                        new BacktestCacheEntry(System.currentTimeMillis() + BACKTEST_CACHE_TTL_MS, data)
                );
                Iterator<String> iterator = backtestCache.keySet().iterator();
                while (backtestCache.size() > BACKTEST_CACHE_MAX_ENTRIES && iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ExceptionEnum.STOCK_STRATEGY_CALC_BUSY);
        } finally {
            if (acquired) {
                backtestPermits.release();
            }
        }
    }

    private String backtestCacheKey(String strategy, Object... parts) {
        StringBuilder sb = new StringBuilder(strategy);
        for (Object part : parts) {
            sb.append('|').append(part == null ? "" : part);
        }
        return sb.toString();
    }

    private static final class BacktestCacheEntry {
        private final long expireAt;
        private final List<StockTradeBacktestVO> data;

        private BacktestCacheEntry(long expireAt, List<StockTradeBacktestVO> data) {
            this.expireAt = expireAt;
            this.data = data;
        }
    }

    private Comparator<StockTradeSignalVO> buildGridSignalComparator(Sort sort) {
        Comparator<StockTradeSignalVO> result = null;
        for (Sort.Order order : sort) {
            Comparator<StockTradeSignalVO> comparator = switch (order.getProperty()) {
                case "code" -> Comparator.comparing(StockTradeSignalVO::getCode);
                case "name" -> Comparator.comparing(StockTradeSignalVO::getName);
                case SIGNAL -> Comparator.comparing(StockTradeSignalVO::getSignal);
                case LATEST_PRICE -> Comparator.comparing(
                        StockTradeSignalVO::getLatestPrice, Comparator.nullsLast(BigDecimal::compareTo));
                case "pir" -> Comparator.comparing(
                        StockTradeSignalVO::getPir, Comparator.nullsLast(BigDecimal::compareTo));
                case "gridReferencePrice" -> Comparator.comparing(
                        StockTradeSignalVO::getGridReferencePrice, Comparator.nullsLast(BigDecimal::compareTo));
                case "lowerGridPrice" -> Comparator.comparing(
                        StockTradeSignalVO::getLowerGridPrice, Comparator.nullsLast(BigDecimal::compareTo));
                case "upperGridPrice" -> Comparator.comparing(
                        StockTradeSignalVO::getUpperGridPrice, Comparator.nullsLast(BigDecimal::compareTo));
                case "gridPosition" -> Comparator.comparing(
                        StockTradeSignalVO::getGridPosition, Comparator.nullsLast(Integer::compareTo));
                default -> null;
            };
            if (comparator != null) {
                if (order.getDirection() == Sort.Direction.DESC) {
                    comparator = comparator.reversed();
                }
                result = result == null ? comparator : result.thenComparing(comparator);
            }
        }
        return result != null ? result : Comparator.comparing(StockTradeSignalVO::getCode);
    }

    private Set<String> loadWatchlistCodes(Long watchlistGroupId) {
        if (watchlistGroupId == null) {
            return null;
        }
        Long userId = UserContext.requireCurrentUserId();
        stockWatchlistGroupRepository.findByIdAndUserId(watchlistGroupId, userId)
                .orElseThrow(() -> new BusinessException(ExceptionEnum.WATCHLIST_GROUP_NOT_FOUND));
        return stockWatchlistStockRepository.findByGroupIdOrderBySortNoDesc(watchlistGroupId)
                .stream().map(StockWatchlistStock::getStockCode).collect(Collectors.toSet());
    }

    private Comparator<StockTradeSignalVO> buildMacdSignalComparator(Sort sort) {
        Comparator<StockTradeSignalVO> result = null;
        for (Sort.Order order : sort) {
            Comparator<StockTradeSignalVO> comparator = switch (order.getProperty()) {
                case "code" -> Comparator.comparing(StockTradeSignalVO::getCode);
                case "name" -> Comparator.comparing(StockTradeSignalVO::getName);
                case SIGNAL -> Comparator.comparing(StockTradeSignalVO::getSignal);
                case "latestPrice" -> Comparator.comparing(
                        StockTradeSignalVO::getLatestPrice, Comparator.nullsLast(BigDecimal::compareTo));
                case "pir" -> Comparator.comparing(
                        StockTradeSignalVO::getPir, Comparator.nullsLast(BigDecimal::compareTo));
                case "dif" -> Comparator.comparing(
                        StockTradeSignalVO::getDif, Comparator.nullsLast(BigDecimal::compareTo));
                case "dea" -> Comparator.comparing(
                        StockTradeSignalVO::getDea, Comparator.nullsLast(BigDecimal::compareTo));
                case "macdHistogram" -> Comparator.comparing(
                        StockTradeSignalVO::getMacdHistogram, Comparator.nullsLast(BigDecimal::compareTo));
                default -> null;
            };
            if (comparator != null) {
                if (order.getDirection() == Sort.Direction.DESC) {
                    comparator = comparator.reversed();
                }
                result = result == null ? comparator : result.thenComparing(comparator);
            }
        }
        return result != null ? result : Comparator.comparing(StockTradeSignalVO::getCode);
    }

    private <T> Page<T> toPage(List<T> result, Pageable pageable) {
        int total = result.size();
        int fromIndex = pageable.getPageNumber() * pageable.getPageSize();
        if (fromIndex >= total) {
            return new PageImpl<>(Collections.emptyList(), pageable, total);
        }
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), total);
        return new PageImpl<>(result.subList(fromIndex, toIndex), pageable, total);
    }

    private boolean hasStrategySortFields(Sort sort) {
        if (!sort.isSorted()) return false;
        for (Sort.Order order : sort) {
            String prop = order.getProperty();
            if (SIGNAL.equals(prop) || "momentumValue".equals(prop) || "dif".equals(prop)
                    || "dea".equals(prop) || "macdHistogram".equals(prop)
                    || "gridReferencePrice".equals(prop) || "lowerGridPrice".equals(prop)
                    || "upperGridPrice".equals(prop) || "gridPosition".equals(prop)
                    || "totalReturn".equals(prop) ||
                "tradeCount".equals(prop) || "winRate".equals(prop) || "pValue".equals(prop)) {
                return true;
            }
        }
        return false;
    }

    private Specification<StockQuote> buildStockQuoteSpec(String code, Set<String> watchlistCodes, String market) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.isNotBlank(market)) {
                predicates.add(cb.like(cb.lower(root.get("code")), market.toLowerCase() + "%"));
            }
            if (StringUtils.isNotBlank(code)) {
                predicates.add(cb.equal(root.get("code"), code));
            }
            if (watchlistCodes != null) {
                List<Predicate> orPreds = new ArrayList<>();
                for (String wc : watchlistCodes) {
                    orPreds.add(cb.like(root.get("code"), "%" + wc));
                }
                predicates.add(cb.or(orPreds.toArray(new Predicate[0])));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

}
