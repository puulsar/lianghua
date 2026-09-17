package com.brotherc.aquant.sync.service;

import com.brotherc.aquant.common.constant.StockConstant;
import com.brotherc.aquant.common.constant.StockSyncConstant;
import com.brotherc.aquant.common.enums.CoreIndexEnum;
import com.brotherc.aquant.common.utils.StockUtils;
import com.brotherc.aquant.index.repository.StockIndexHistoryRepository;
import com.brotherc.aquant.industry.repository.StockIndustryBoardHistoryRepository;
import com.brotherc.aquant.industry.repository.StockIndustryBoardRepository;
import com.brotherc.aquant.stock.repository.StockQuoteHistoryRepository;
import com.brotherc.aquant.stock.repository.StockQuoteRepository;
import com.brotherc.aquant.sync.entity.StockDataHealthCheck;
import com.brotherc.aquant.sync.entity.StockDataHealthCheckItem;
import com.brotherc.aquant.sync.entity.StockSync;
import com.brotherc.aquant.sync.model.vo.DataHealthCheckItemVO;
import com.brotherc.aquant.sync.model.vo.DataHealthCheckVO;
import com.brotherc.aquant.sync.repository.StockDataHealthCheckItemRepository;
import com.brotherc.aquant.sync.repository.StockDataHealthCheckRepository;
import com.brotherc.aquant.sync.repository.StockSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当日数据体检：收盘收口作业跑完后，用一组硬指标确认数据是真的「齐、准、可用」，
 * 而不是「跑完了没报错」。
 * <p>
 * 设计要点：
 * <ol>
 *     <li>只做只读校验，不做任何修复——修复留给同步作业按水位自然补齐，避免体检副作用。</li>
 *     <li>每项都要给出「期望值 / 实际值」，前端能直接展示，运维看日志也知道差在哪。</li>
 *     <li>结论分三档：PASS（可用）/ WARN（能用但要留意）/ FAIL（当日数据不可信）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDataHealthCheckService {

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_WARN = "WARN";
    public static final String STATUS_FAIL = "FAIL";

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** 全市场股票数低于该值视为行情快照退化 */
    private static final long QUOTE_COUNT_FAIL_THRESHOLD = 1000L;
    private static final long QUOTE_COUNT_WARN_THRESHOLD = 4000L;

    /** 当日日 K 覆盖率阈值 */
    private static final double KLINE_COVERAGE_WARN = 0.85D;
    private static final double KLINE_COVERAGE_PASS = 0.95D;

    /** 板块历史覆盖率阈值 */
    private static final double BOARD_COVERAGE_WARN = 0.90D;

    /** 成交额环比偏离告警阈值（较上一交易日） */
    private static final double TURNOVER_DEVIATION_WARN = 0.60D;

    private final StockQuoteRepository stockQuoteRepository;
    private final StockQuoteHistoryRepository stockQuoteHistoryRepository;
    private final StockIndustryBoardRepository stockIndustryBoardRepository;
    private final StockIndustryBoardHistoryRepository stockIndustryBoardHistoryRepository;
    private final StockIndexHistoryRepository stockIndexHistoryRepository;
    private final StockSyncRepository stockSyncRepository;
    private final StockDataHealthCheckRepository healthCheckRepository;
    private final StockDataHealthCheckItemRepository healthCheckItemRepository;

    /**
     * 执行一次体检并落库。
     *
     * @param tradeDate   被体检的交易日
     * @param triggerType SCHEDULED / MANUAL
     */
    @Transactional
    public DataHealthCheckVO runCheck(LocalDate tradeDate, String triggerType) {
        long start = System.currentTimeMillis();
        String date = tradeDate.toString();
        List<StockDataHealthCheckItem> items = new ArrayList<>();

        items.add(checkQuoteSnapshot(date));
        items.add(checkDailyKlineCoverage(date));
        items.add(checkDailyKlineValidity(date));
        items.add(checkBoardHistory(date));
        items.add(checkIndexHistory(tradeDate));
        items.add(checkTurnover(date));
        items.add(checkSyncWatermarks(tradeDate));

        int pass = 0;
        int warn = 0;
        int fail = 0;
        for (StockDataHealthCheckItem item : items) {
            if (STATUS_FAIL.equals(item.getStatus())) {
                fail++;
            } else if (STATUS_WARN.equals(item.getStatus())) {
                warn++;
            } else {
                pass++;
            }
        }
        String status = fail > 0 ? STATUS_FAIL : (warn > 0 ? STATUS_WARN : STATUS_PASS);
        long durationMillis = System.currentTimeMillis() - start;

        StockDataHealthCheck record = new StockDataHealthCheck();
        record.setTradeDate(date);
        record.setCheckTime(LocalDateTime.now());
        record.setStatus(status);
        record.setPassCount(pass);
        record.setWarnCount(warn);
        record.setFailCount(fail);
        record.setDurationMillis(durationMillis);
        record.setTriggerType(triggerType);
        record.setSummary(buildSummary(status, pass, warn, fail, items));
        healthCheckRepository.save(record);

        for (int i = 0; i < items.size(); i++) {
            StockDataHealthCheckItem item = items.get(i);
            item.setCheckId(record.getId());
            item.setSortOrder(i + 1);
        }
        healthCheckItemRepository.saveAll(items);

        log.info("数据体检完成: tradeDate={}, status={}, pass={}, warn={}, fail={}, duration={}ms, summary={}",
                date, status, pass, warn, fail, durationMillis, record.getSummary());
        return toVO(record, items);
    }

    /** 读取最近一次体检结果 */
    @Transactional(readOnly = true)
    public DataHealthCheckVO getLatest() {
        List<StockDataHealthCheck> records = healthCheckRepository.findTop20ByOrderByCheckTimeDesc();
        if (records == null || records.isEmpty()) {
            return null;
        }
        return toVO(records.get(0), healthCheckItemRepository
                .findByCheckIdOrderBySortOrderAsc(records.get(0).getId()));
    }

    /** 读取最近若干次体检结果（历史趋势） */
    @Transactional(readOnly = true)
    public List<DataHealthCheckVO> getRecent(int limit) {
        List<StockDataHealthCheck> records = healthCheckRepository.findTop20ByOrderByCheckTimeDesc();
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<StockDataHealthCheck> sliced = records.size() > limit ? records.subList(0, limit) : records;
        List<DataHealthCheckVO> result = new ArrayList<>();
        for (StockDataHealthCheck record : sliced) {
            // 历史列表不需要明细，避免一次拉全量明细
            result.add(toVO(record, List.of()));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 各检查项
    // ------------------------------------------------------------------

    /**
     * 实时行情快照：全市场股票数量是否退化、收盘快照是否真的落到今天。
     */
    private StockDataHealthCheckItem checkQuoteSnapshot(String tradeDate) {
        long quoteCount;
        try {
            quoteCount = stockQuoteRepository.count();
        } catch (Exception e) {
            log.warn("体检读取 stock_quote 行数失败", e);
            return item("QUOTE_SNAPSHOT", "实时行情快照", STATUS_FAIL, "≥" + QUOTE_COUNT_WARN_THRESHOLD + " 只",
                    "读取失败", "统计 stock_quote 行数时异常，请检查数据库连接");
        }
        Long watermark = readWatermark(StockSyncConstant.STOCK_DAILY_LATEST);
        boolean fresh = watermark != null && watermark >= closeTimestamp(tradeDate);
        String actual = quoteCount + " 只，水位" + (watermark == null ? "缺失" : (fresh ? "已更新" : "未更新"));

        if (quoteCount < QUOTE_COUNT_FAIL_THRESHOLD || !fresh) {
            return item("QUOTE_SNAPSHOT", "实时行情快照", STATUS_FAIL,
                    "≥" + QUOTE_COUNT_WARN_THRESHOLD + " 只且收盘后已刷新", actual,
                    "收盘快照未落库或股票数量严重退化，仪表盘会显示昨日数据");
        }
        if (quoteCount < QUOTE_COUNT_WARN_THRESHOLD) {
            return item("QUOTE_SNAPSHOT", "实时行情快照", STATUS_WARN,
                    "≥" + QUOTE_COUNT_WARN_THRESHOLD + " 只", actual,
                    "股票数量低于常规水平，可能是上游只返回了部分快照");
        }
        return item("QUOTE_SNAPSHOT", "实时行情快照", STATUS_PASS,
                "≥" + QUOTE_COUNT_WARN_THRESHOLD + " 只且收盘后已刷新", actual, "收盘快照已刷新");
    }

    /**
     * 当日日 K 覆盖率：stock_quote_history 当天有 K 线的股票占全市场比例。
     * 这是「收盘后统计当日所有数据」最核心的一条——缺了它所有按日 K 的功能都是昨天的数据。
     */
    private StockDataHealthCheckItem checkDailyKlineCoverage(String tradeDate) {
        long total = stockQuoteRepository.count();
        long covered = stockQuoteHistoryRepository.countDistinctCodeByTradeDate(tradeDate);
        double ratio = total <= 0 ? 0D : (double) covered / total;
        String actual = covered + "/" + total + "（" + percent(ratio) + "）";

        if (ratio < KLINE_COVERAGE_WARN) {
            return item("DAILY_KLINE_COVERAGE", "当日日K覆盖率", STATUS_FAIL,
                    "≥" + percent(KLINE_COVERAGE_WARN), actual,
                    "当日日K线大面积缺失，回测/涨跌幅/历史行情都会读到旧数据，需要检查历史回补");
        }
        if (ratio < KLINE_COVERAGE_PASS) {
            return item("DAILY_KLINE_COVERAGE", "当日日K覆盖率", STATUS_WARN,
                    "≥" + percent(KLINE_COVERAGE_PASS), actual, "部分股票当日日K线缺失，下次同步会按水位自动补齐");
        }
        return item("DAILY_KLINE_COVERAGE", "当日日K覆盖率", STATUS_PASS,
                "≥" + percent(KLINE_COVERAGE_PASS), actual, "当日日K线覆盖正常");
    }

    /**
     * 假 K 线检查：开/高/低/成交量任一为 0（停牌股占位快照被误落库）。
     */
    private StockDataHealthCheckItem checkDailyKlineValidity(String tradeDate) {
        long invalid = stockQuoteHistoryRepository.countInvalidQuoteByTradeDate(tradeDate);
        if (invalid > 0) {
            return item("DAILY_KLINE_VALIDITY", "日K数据有效性", STATUS_FAIL, "0 条异常", invalid + " 条",
                    "存在开/高/低/成交量为 0 的假 K 线（多为停牌股占位快照），会污染按日K计算的指标");
        }
        return item("DAILY_KLINE_VALIDITY", "日K数据有效性", STATUS_PASS, "0 条异常", "0 条", "无全 0 占位 K 线");
    }

    /**
     * 板块历史：当天有多少个板块落了历史 K 线。
     * 上游（同花顺）盘后会有一段时间返回空数组，这里必须显式查出来，否则缺口是静默的。
     */
    private StockDataHealthCheckItem checkBoardHistory(String tradeDate) {
        long total = stockIndustryBoardRepository.count();
        long covered = stockIndustryBoardHistoryRepository.countDistinctSectorByTradeDate(tradeDate);
        double ratio = total <= 0 ? 0D : (double) covered / total;
        String actual = covered + "/" + total + "（" + percent(ratio) + "）";

        if (ratio < BOARD_COVERAGE_WARN) {
            return item("BOARD_HISTORY", "板块历史K线", STATUS_FAIL, "≥" + percent(BOARD_COVERAGE_WARN), actual,
                    "板块历史大面积缺失，近5日成交额会少一天，需检查同花顺上游是否已发布当日数据");
        }
        if (covered < total) {
            return item("BOARD_HISTORY", "板块历史K线", STATUS_WARN, total + " 个板块", actual,
                    "个别板块缺失当日历史，近5日成交额会回退个股汇总兜底");
        }
        return item("BOARD_HISTORY", "板块历史K线", STATUS_PASS, total + " 个板块", actual, "板块历史齐全");
    }

    /** 核心指数当日 K 线是否齐全 */
    private StockDataHealthCheckItem checkIndexHistory(LocalDate tradeDate) {
        Collection<String> codes = CoreIndexEnum.getCodes();
        long covered = stockIndexHistoryRepository.countByTradeDateAndIndexCodeIn(tradeDate, codes);
        long missing = codes.size() - covered;
        String actual = covered + "/" + codes.size();

        if (missing >= 3) {
            return item("INDEX_HISTORY", "核心指数日K", STATUS_FAIL, codes.size() + " 个指数", actual,
                    "核心指数日K缺失较多，指数卡片/大盘走势会显示旧数据");
        }
        if (missing > 0) {
            return item("INDEX_HISTORY", "核心指数日K", STATUS_WARN, codes.size() + " 个指数", actual,
                    "有 " + missing + " 个核心指数当日日K缺失");
        }
        return item("INDEX_HISTORY", "核心指数日K", STATUS_PASS, codes.size() + " 个指数", actual, "核心指数日K齐全");
    }

    /**
     * 全市场成交额合理性：必须 > 0，且与上一交易日偏离不能过于离谱（单位错误/只同步了部分股票会暴露在这里）。
     */
    private StockDataHealthCheckItem checkTurnover(String tradeDate) {
        List<String> prevDates = stockQuoteHistoryRepository.findRecentTradeDatesBefore(tradeDate, 2);
        String prevDate = null;
        for (String d : prevDates) {
            if (!d.equals(tradeDate)) {
                prevDate = d;
                break;
            }
        }
        List<String> dates = new ArrayList<>();
        dates.add(tradeDate);
        if (prevDate != null) {
            dates.add(prevDate);
        }

        Map<String, BigDecimal> byDate = new HashMap<>();
        for (Object[] row : stockIndustryBoardHistoryRepository.sumAmountByTradeDates(dates)) {
            if (row != null && row[0] != null && row[1] != null) {
                byDate.put(String.valueOf(row[0]), new BigDecimal(String.valueOf(row[1])));
            }
        }
        // 板块历史缺失时回退个股成交额汇总（与大盘「近5日成交额」同一兜底口径）
        for (Object[] row : stockQuoteHistoryRepository.sumTurnoverByTradeDates(dates)) {
            if (row != null && row[0] != null && row[1] != null) {
                byDate.putIfAbsent(String.valueOf(row[0]), new BigDecimal(String.valueOf(row[1])));
            }
        }

        BigDecimal today = byDate.get(tradeDate);
        if (today == null || today.compareTo(BigDecimal.ZERO) <= 0) {
            return item("TURNOVER_SANITY", "全市场成交额", STATUS_FAIL, "> 0", today == null ? "无数据" : "0",
                    "当日全市场成交额为空，成交额类图表会缺柱或显示 0");
        }
        String actual = today.divide(new BigDecimal("1E12"), 2, RoundingMode.HALF_UP) + " 万亿";

        BigDecimal prev = prevDate == null ? null : byDate.get(prevDate);
        if (prev == null || prev.compareTo(BigDecimal.ZERO) <= 0) {
            return item("TURNOVER_SANITY", "全市场成交额", STATUS_PASS, "> 0 且环比偏离 ≤ 60%", actual,
                    "缺少上一交易日成交额，跳过环比校验");
        }
        double deviation = today.subtract(prev).abs().divide(prev, 4, RoundingMode.HALF_UP).doubleValue();
        if (deviation > TURNOVER_DEVIATION_WARN) {
            return item("TURNOVER_SANITY", "全市场成交额", STATUS_WARN, "环比偏离 ≤ 60%",
                    actual + "，环比 " + percent(deviation),
                    "较上一交易日偏离过大，请确认是否只有部分股票完成了回补");
        }
        return item("TURNOVER_SANITY", "全市场成交额", STATUS_PASS, "> 0 且环比偏离 ≤ 60%",
                actual + "，环比 " + percent(deviation), "成交额量级正常");
    }

    /** 关键同步水位是否都推进到当日收盘后 */
    private StockDataHealthCheckItem checkSyncWatermarks(LocalDate tradeDate) {
        List<String> names = Arrays.asList(
                StockSyncConstant.STOCK_DAILY_LATEST,
                StockSyncConstant.STOCK_INDEX_LATEST,
                StockSyncConstant.STOCK_BOARD_INDUSTRY_LATEST);
        long closeTs = closeTimestamp(tradeDate.toString());

        int advanced = 0;
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            Long ts = readWatermark(name);
            if (ts != null && ts >= closeTs) {
                advanced++;
            } else {
                missing.add(name);
            }
        }
        String actual = advanced + "/" + names.size() + " 已推进";

        if (advanced == 0) {
            return item("SYNC_WATERMARK", "同步水位", STATUS_FAIL, names.size() + " 个水位全部推进", actual,
                    "同步水位均未推进到收盘后，说明收盘作业基本没跑成");
        }
        if (advanced < names.size()) {
            return item("SYNC_WATERMARK", "同步水位", STATUS_WARN, names.size() + " 个水位全部推进",
                    actual + "（未推进：" + String.join(", ", missing) + "）", "部分数据源收盘后未完成刷新");
        }
        return item("SYNC_WATERMARK", "同步水位", STATUS_PASS, names.size() + " 个水位全部推进", actual, "同步水位已全部推进");
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private Long readWatermark(String name) {
        StockSync stockSync = stockSyncRepository.findByName(name);
        return StockUtils.parseSyncTimestamp(stockSync);
    }

    /** 交易日收盘时刻（15:00）的毫秒时间戳 */
    private long closeTimestamp(String tradeDate) {
        return LocalDate.parse(tradeDate).atTime(StockConstant.A_SHARE_MARKET_CLOSE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static StockDataHealthCheckItem item(String key, String name, String status,
                                                 String expected, String actual, String message) {
        StockDataHealthCheckItem item = new StockDataHealthCheckItem();
        item.setItemKey(key);
        item.setItemName(name);
        item.setStatus(status);
        item.setExpected(expected);
        item.setActual(actual);
        item.setMessage(message);
        return item;
    }

    private static String percent(double ratio) {
        return BigDecimal.valueOf(ratio * 100).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static String buildSummary(String status, int pass, int warn, int fail,
                                       List<StockDataHealthCheckItem> items) {
        if (STATUS_PASS.equals(status)) {
            return "全部 " + pass + " 项检查通过，当日数据完整";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(STATUS_FAIL.equals(status) ? "存在 " + fail + " 项异常" : "存在 " + warn + " 项告警");
        sb.append("（通过 ").append(pass).append(" 项）：");
        List<String> problems = new ArrayList<>();
        for (StockDataHealthCheckItem item : items) {
            if (!STATUS_PASS.equals(item.getStatus())) {
                problems.add(item.getItemName() + "[" + item.getStatus() + "]");
            }
        }
        sb.append(String.join("、", problems));
        return sb.toString();
    }

    private static DataHealthCheckVO toVO(StockDataHealthCheck record, List<StockDataHealthCheckItem> items) {
        DataHealthCheckVO vo = new DataHealthCheckVO();
        vo.setId(record.getId());
        vo.setTradeDate(record.getTradeDate());
        vo.setCheckTime(record.getCheckTime());
        vo.setStatus(record.getStatus());
        vo.setPassCount(record.getPassCount());
        vo.setWarnCount(record.getWarnCount());
        vo.setFailCount(record.getFailCount());
        vo.setDurationMillis(record.getDurationMillis());
        vo.setTriggerType(record.getTriggerType());
        vo.setSummary(record.getSummary());

        List<DataHealthCheckItemVO> itemVOs = new ArrayList<>();
        if (items != null) {
            for (StockDataHealthCheckItem item : items) {
                DataHealthCheckItemVO itemVO = new DataHealthCheckItemVO();
                itemVO.setItemKey(item.getItemKey());
                itemVO.setItemName(item.getItemName());
                itemVO.setStatus(item.getStatus());
                itemVO.setExpected(item.getExpected());
                itemVO.setActual(item.getActual());
                itemVO.setMessage(item.getMessage());
                itemVO.setSortOrder(item.getSortOrder());
                itemVOs.add(itemVO);
            }
        }
        vo.setItems(itemVOs);
        return vo;
    }

}
