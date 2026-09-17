package com.brotherc.aquant.stock.service;

import com.brotherc.aquant.stock.entity.StockQuoteHistory;
import com.brotherc.aquant.integration.akshare.model.StockZhADaily;
import com.brotherc.aquant.integration.akshare.model.StockZhASpot;
import com.brotherc.aquant.stock.repository.StockQuoteHistoryRepository;
import com.brotherc.aquant.common.utils.StockHelper;
import com.brotherc.aquant.common.utils.StockUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockQuoteHistoryService {

    private final StockQuoteHistoryRepository stockQuoteHistoryRepository;
    private final StockHelper stockHelper;

    @Transactional(rollbackFor = Exception.class)
    public void save(List<StockZhASpot> stockZhASpotList, LocalDateTime now) {
        if (CollectionUtils.isEmpty(stockZhASpotList)) {
            return;
        }

        if (!stockHelper.isClosedDailyQuoteAvailable(now)) {
            log.info("当前交易日尚未收盘，跳过实时行情写入日 K，time={}", now);
            return;
        }

        // 获取最近已收盘交易日
        String tradeDate = stockHelper.latestClosedTradeDay(now).toString();

        // 提取所有股票代码；如果上游异常返回重复代码，保留最后一条行情。
        // 停牌/无行情的股票，实时接口返回的是开高低收与成交量全为 0 的占位行；
        // 这类行绝不能当日 K 落库：会在图上画出一根「从 0 拉到昨收」的假 K 线
        // （2026-09-11 / 09-15 各有一批停牌股被这样写坏），也会把已有的正确行覆盖成 0。
        // 正常成交的股票成交量必然 > 0，据此把占位行挡在库外（当日上游日线本来也不会有这两天）。
        Map<String, StockZhASpot> spotMap = new LinkedHashMap<>();
        int invalidCount = 0;
        for (StockZhASpot spot : stockZhASpotList) {
            if (spot == null || spot.getCode() == null) {
                continue;
            }
            if (!isValidSpotQuote(spot)) {
                invalidCount++;
                continue;
            }
            spotMap.put(spot.getCode(), spot);
        }
        if (invalidCount > 0) {
            log.warn("实时行情中有 {} 条无有效成交的占位数据（停牌/无行情），本次跳过写入日 K，tradeDate={}",
                    invalidCount, tradeDate);
        }
        if (spotMap.isEmpty()) {
            return;
        }

        // 一次性查出该交易日期已存在的数据
        List<String> codes = new ArrayList<>(spotMap.keySet());
        List<StockQuoteHistory> existedList = stockQuoteHistoryRepository.findByTradeDateAndCodeIn(tradeDate, codes);

        // 构建 Map：code -> entity
        Map<String, StockQuoteHistory> existedMap = new LinkedHashMap<>();
        List<Long> duplicateIds = new ArrayList<>();
        for (StockQuoteHistory existed : existedList) {
            mergeExisting(existedMap, duplicateIds, existed.getCode(), existed);
        }
        deleteDuplicateHistories(duplicateIds);

        List<StockQuoteHistory> saveList = new ArrayList<>(spotMap.size());

        // 遍历实时行情
        for (StockZhASpot spot : spotMap.values()) {
            StockQuoteHistory entity = existedMap.get(spot.getCode());

            if (entity == null) {
                entity = new StockQuoteHistory();
                entity.setCode(spot.getCode());
                entity.setTradeDate(tradeDate);
            }

            // 行情更新
            entity.setName(spot.getName());
            entity.setClosePrice(spot.getLatestPrice());
            entity.setOpenPrice(spot.getOpenPrice());
            entity.setHighPrice(spot.getHighPrice());
            entity.setLowPrice(spot.getLowPrice());
            entity.setVolume(spot.getVolume());
            entity.setTurnover(spot.getTurnover());
            entity.setQuoteTime(spot.getTimestamp());
            entity.setCreatedAt(now);

            saveList.add(entity);
        }

        // 批量保存
        stockQuoteHistoryRepository.saveAll(saveList);
    }

    /**
     * 实时行情里这一条是否代表「当天真的成交过」。
     *
     * <p>停牌 / 无行情的股票，上游返回的是开高低收与成交量全为 0 的占位行（部分股票连最新价也是 0），
     * 必须过滤掉，否则会在日 K 里造出一根从 0 拉到昨收的假 K 线，并覆盖掉原本正确的行。
     * 正常成交（含一字板）的股票开高低收与成交量都必然大于 0，所以这个条件不会误伤。</p>
     */
    private boolean isValidSpotQuote(StockZhASpot spot) {
        return isPositive(spot.getOpenPrice())
                && isPositive(spot.getHighPrice())
                && isPositive(spot.getLowPrice())
                && isPositive(spot.getLatestPrice())
                && isPositive(spot.getVolume());
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(List<StockZhADaily> stockZhAHists, String code, String name, LocalDateTime now) {
        if (CollectionUtils.isEmpty(stockZhAHists)) {
            return;
        }

        // 历史接口按闭区间返回；按交易日去重后再 upsert，保证重复同步不会新增重复K线。
        Map<String, StockZhADaily> dailyMap = new LinkedHashMap<>();
        for (StockZhADaily daily : stockZhAHists) {
            String tradeDate = parseTradeDate(daily);
            if (tradeDate != null) {
                dailyMap.put(tradeDate, daily);
            }
        }
        if (dailyMap.isEmpty()) {
            return;
        }

        List<String> tradeDates = new ArrayList<>(dailyMap.keySet());
        List<StockQuoteHistory> existedList = stockQuoteHistoryRepository.findByCodeAndTradeDateIn(code, tradeDates);
        Map<String, StockQuoteHistory> existedMap = new LinkedHashMap<>();
        List<Long> duplicateIds = new ArrayList<>();
        for (StockQuoteHistory existed : existedList) {
            mergeExisting(existedMap, duplicateIds, existed.getTradeDate(), existed);
        }
        deleteDuplicateHistories(duplicateIds);

        List<StockQuoteHistory> saveList = new ArrayList<>(dailyMap.size());
        for (Map.Entry<String, StockZhADaily> entry : dailyMap.entrySet()) {
            String tradeDate = entry.getKey();
            StockZhADaily daily = entry.getValue();
            StockQuoteHistory stockQuoteHistory = existedMap.get(tradeDate);
            if (stockQuoteHistory == null) {
                stockQuoteHistory = new StockQuoteHistory();
                stockQuoteHistory.setCode(code);
                stockQuoteHistory.setTradeDate(tradeDate);
            }

            stockQuoteHistory.setName(name);
            stockQuoteHistory.setOpenPrice(daily.getOpen());
            stockQuoteHistory.setClosePrice(daily.getClose());
            stockQuoteHistory.setHighPrice(daily.getHigh());
            stockQuoteHistory.setLowPrice(daily.getLow());
            stockQuoteHistory.setVolume(daily.getVolume());
            // akshare 个股日线里 turnover 字段是「换手率(%)」，成交额在 amount 字段；
            // 本表 turnover 列语义为「成交额(元)」，必须取 amount，否则历史 K 线成交额全部失真。
            stockQuoteHistory.setTurnover(daily.getAmount());
            stockQuoteHistory.setQuoteTime("15:00:00");
            stockQuoteHistory.setCreatedAt(now);
            saveList.add(stockQuoteHistory);
        }

        stockQuoteHistoryRepository.saveAll(saveList);
    }

    private String parseTradeDate(StockZhADaily daily) {
        if (daily == null || daily.getDate() == null || daily.getDate().length() < 10) {
            return null;
        }
        return daily.getDate().substring(0, 10);
    }

    private void mergeExisting(
            Map<String, StockQuoteHistory> existedMap,
            List<Long> duplicateIds,
            String key,
            StockQuoteHistory candidate
    ) {
        if (key == null || candidate == null) {
            return;
        }

        StockQuoteHistory current = existedMap.get(key);
        if (current == null) {
            existedMap.put(key, candidate);
            return;
        }

        StockQuoteHistory keep = chooseLatest(current, candidate);
        StockQuoteHistory duplicate = keep == current ? candidate : current;
        if (duplicate.getId() != null) {
            duplicateIds.add(duplicate.getId());
        }
        existedMap.put(key, keep);
    }

    private StockQuoteHistory chooseLatest(StockQuoteHistory current, StockQuoteHistory candidate) {
        Long currentId = current.getId();
        Long candidateId = candidate.getId();
        if (currentId == null) {
            return candidate;
        }
        if (candidateId == null) {
            return current;
        }
        return candidateId > currentId ? candidate : current;
    }

    private void deleteDuplicateHistories(List<Long> duplicateIds) {
        if (!CollectionUtils.isEmpty(duplicateIds)) {
            stockQuoteHistoryRepository.deleteAllByIdInBatch(duplicateIds);
        }
    }

    /**
     * 获取个股历史行情 (支持周期聚合)
     *
     * @param code      股票代码
     * @param frequency 频率: 1d, 1w, 1M, 1Q, 1Y
     * @return 历史数据列表
     */
    public List<StockQuoteHistory> getHistory(String code, String frequency) {
        code = StockUtils.wrapExchangePrefix(code);
        List<StockQuoteHistory> dailyList = stockQuoteHistoryRepository.findByCodeOrderByTradeDateAsc(code);

        if ("1d".equals(frequency) || CollectionUtils.isEmpty(dailyList)) {
            return dailyList;
        }

        // 聚合逻辑
        Map<String, List<StockQuoteHistory>> groupedMap = new LinkedHashMap<>();
        for (StockQuoteHistory item : dailyList) {
            String dateStr = item.getTradeDate();
            // 处理可能的非标准日期格式，这里假设是 "yyyy-MM-dd"
            LocalDate date = LocalDate.parse(dateStr);
            String key;
            switch (frequency) {
                case "1w" -> key = date.getYear() + "-W" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                case "1M" -> key = date.getYear() + "-" + date.getMonthValue();
                case "1Q" -> key = date.getYear() + "-Q" + date.get(IsoFields.QUARTER_OF_YEAR);
                case "1Y" -> key = String.valueOf(date.getYear());
                default -> key = dateStr;
            }
            groupedMap.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        List<StockQuoteHistory> result = new ArrayList<>();

        for (List<StockQuoteHistory> group : groupedMap.values()) {
            if (group.isEmpty())
                continue;

            StockQuoteHistory first = group.get(0);
            StockQuoteHistory last = group.get(group.size() - 1);

            StockQuoteHistory aggregated = new StockQuoteHistory();
            aggregated.setCode(first.getCode());
            aggregated.setName(first.getName());
            aggregated.setTradeDate(last.getTradeDate());

            // OHLC
            aggregated.setOpenPrice(first.getOpenPrice());
            aggregated.setClosePrice(last.getClosePrice());
            aggregated.setHighPrice(group.stream().map(StockQuoteHistory::getHighPrice).max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO));
            aggregated.setLowPrice(group.stream().map(StockQuoteHistory::getLowPrice).min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO));

            // Volume & Turnover
            aggregated.setVolume(
                    group.stream().map(StockQuoteHistory::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add));
            aggregated.setTurnover(
                    group.stream().map(StockQuoteHistory::getTurnover).reduce(BigDecimal.ZERO, BigDecimal::add));

            result.add(aggregated);
        }

        return result;
    }

}
