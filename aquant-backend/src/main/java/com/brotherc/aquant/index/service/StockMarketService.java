package com.brotherc.aquant.index.service;

import com.brotherc.aquant.industry.entity.StockIndustryBoard;
import com.brotherc.aquant.stock.model.dto.StockQuoteSentimentDTO;
import com.brotherc.aquant.index.model.vo.DailyTurnoverItem;
import com.brotherc.aquant.index.model.vo.FundFlowGraphLinkVO;
import com.brotherc.aquant.index.model.vo.FundFlowGraphNodeVO;
import com.brotherc.aquant.index.model.vo.FundFlowGraphVO;
import com.brotherc.aquant.index.model.vo.FundFlowSummaryVO;
import com.brotherc.aquant.index.model.vo.MarketSentimentVO;
import com.brotherc.aquant.industry.repository.StockIndustryBoardHistoryRepository;
import com.brotherc.aquant.industry.repository.StockIndustryBoardRepository;
import com.brotherc.aquant.stock.repository.StockQuoteHistoryRepository;
import com.brotherc.aquant.stock.repository.StockQuoteRepository;
import com.brotherc.aquant.stock.repository.StockTradeCalendarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockMarketService {

    /** 「近5日成交额」展示天数 */
    private static final int RECENT_TURNOVER_DAYS = 5;
    /** 取日期轴时为今日补位多查一天 */
    private static final int TURNOVER_AXIS_QUERY_DAYS = 6;
    private static final BigDecimal YI_YUAN = new BigDecimal("1000000000000");

    private final StockIndustryBoardRepository stockIndustryBoardRepository;
    private final StockIndustryBoardHistoryRepository stockIndustryBoardHistoryRepository;
    private final StockQuoteRepository stockQuoteRepository;
    private final StockQuoteHistoryRepository stockQuoteHistoryRepository;
    private final StockTradeCalendarRepository stockTradeCalendarRepository;

    public FundFlowGraphVO getGraphData() {
        List<StockIndustryBoard> boards = stockIndustryBoardRepository.findAll();
        FundFlowGraphVO vo = new FundFlowGraphVO();
        if (CollectionUtils.isEmpty(boards)) {
            return vo;
        }

        // 挑选交易活跃或净流入/流出较大的板块（前 30 个）
        List<StockIndustryBoard> activeBoards = boards.stream()
                .filter(b -> b.getNetInflow() != null || b.getTotalAmount() != null)
                .sorted(Comparator.comparing(
                        (StockIndustryBoard b) -> b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO
                ).reversed())
                .limit(30)
                .toList();

        if (activeBoards.isEmpty()) {
            activeBoards = boards.stream().limit(20).toList();
        }

        // 计算最大最小成交额，用于归一化计算气泡大小 symbolSize (35 ~ 85)
        BigDecimal maxAmount = activeBoards.stream()
                .map(StockIndustryBoard::getTotalAmount)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(new BigDecimal("1000000000"));

        BigDecimal minAmount = activeBoards.stream()
                .map(StockIndustryBoard::getTotalAmount)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal amountRange = maxAmount.subtract(minAmount);
        if (amountRange.compareTo(BigDecimal.ZERO) == 0) {
            amountRange = BigDecimal.ONE;
        }

        List<FundFlowGraphNodeVO> nodes = new ArrayList<>();
        List<FundFlowGraphLinkVO> links = new ArrayList<>();

        Map<String, String> boardNodeIdMap = new HashMap<>();

        // 构建行业板块节点 (Board Nodes)
        for (StockIndustryBoard b : activeBoards) {
            String nodeId = "board_" + b.getSectorName();
            boardNodeIdMap.put(b.getSectorName(), nodeId);

            FundFlowGraphNodeVO node = new FundFlowGraphNodeVO();
            node.setId(nodeId);
            node.setName(b.getSectorName());
            node.setCategory("board");
            node.setChangePercent(b.getChangePercent());
            node.setNetInflow(b.getNetInflow());
            node.setTotalAmount(b.getTotalAmount());
            node.setCode(b.getLeadingStock());

            // 动态气泡尺寸计算 (适中比例以容纳所有板块)
            int symbolSize = 40;
            if (b.getTotalAmount() != null) {
                double ratio = b.getTotalAmount().subtract(minAmount)
                        .divide(amountRange, 4, RoundingMode.HALF_UP).doubleValue();
                symbolSize = (int) (35 + ratio * 35);
            }
            node.setSymbolSize(symbolSize);
            nodes.add(node);
        }

        // 构建板块间的资金轮动流向连线 (Net Outflow Boards ➔ Net Inflow Boards)
        List<StockIndustryBoard> outflowBoards = activeBoards.stream()
                .filter(b -> b.getNetInflow() != null && b.getNetInflow().compareTo(BigDecimal.ZERO) < 0)
                // 净流出最多在前
                .sorted(Comparator.comparing(StockIndustryBoard::getNetInflow))
                .toList();

        List<StockIndustryBoard> inflowBoards = activeBoards.stream()
                .filter(b -> b.getNetInflow() != null && b.getNetInflow().compareTo(BigDecimal.ZERO) > 0)
                // 净流入最多在前
                .sorted(Comparator.comparing(StockIndustryBoard::getNetInflow).reversed())
                .toList();

        int linkCount = Math.min(outflowBoards.size(), inflowBoards.size());
        for (int i = 0; i < linkCount; i++) {
            StockIndustryBoard outflow = outflowBoards.get(i);
            StockIndustryBoard inflow = inflowBoards.get(i);

            String sourceId = boardNodeIdMap.get(outflow.getSectorName());
            String targetId = boardNodeIdMap.get(inflow.getSectorName());

            if (sourceId != null && targetId != null && !sourceId.equals(targetId)) {
                FundFlowGraphLinkVO link = new FundFlowGraphLinkVO();
                link.setSource(sourceId);
                link.setTarget(targetId);
                BigDecimal flowValue = outflow.getNetInflow().abs().min(inflow.getNetInflow().abs());
                link.setValue(flowValue);
                link.setWeight(Math.min(10, Math.max(2, i + 1)));
                link.setLabel("板块博弈");
                links.add(link);
            }
        }

        vo.setNodes(nodes);
        vo.setLinks(links);
        return vo;
    }

    public FundFlowSummaryVO getSummaryData() {
        List<StockIndustryBoard> boards = stockIndustryBoardRepository.findAll();
        FundFlowSummaryVO summary = new FundFlowSummaryVO();

        if (CollectionUtils.isEmpty(boards)) {
            return summary;
        }

        // 大盘总成交额
        BigDecimal totalMarketAmount = boards.stream()
                .map(StockIndustryBoard::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 全市场上涨/下跌家数
        int riseTotal = boards.stream().mapToInt(b -> b.getRiseCount() != null ? b.getRiseCount() : 0).sum();
        int fallTotal = boards.stream().mapToInt(b -> b.getFallCount() != null ? b.getFallCount() : 0).sum();

        // 净流入最高的板块
        List<StockIndustryBoard> sortedByInflow = boards.stream()
                .filter(b -> b.getNetInflow() != null)
                .sorted(Comparator.comparing(StockIndustryBoard::getNetInflow).reversed())
                .toList();

        if (!sortedByInflow.isEmpty()) {
            StockIndustryBoard topInflow = sortedByInflow.get(0);
            summary.setTopInflowSector(topInflow.getSectorName());
            summary.setTopInflowAmount(topInflow.getNetInflow());
        }

        if (!sortedByInflow.isEmpty()) {
            StockIndustryBoard topOutflow = sortedByInflow.get(sortedByInflow.size() - 1);
            summary.setTopOutflowSector(topOutflow.getSectorName());
            summary.setTopOutflowAmount(topOutflow.getNetInflow());
        }

        summary.setTotalMarketAmount(totalMarketAmount);
        summary.setRiseCountTotal(riseTotal);
        summary.setFallCountTotal(fallTotal);

        // Top 5 净流入板块
        List<FundFlowGraphNodeVO> topInflowNodes = sortedByInflow.stream()
                .limit(5)
                .map(this::toNodeVO)
                .toList();

        // Top 5 净流出板块
        List<FundFlowGraphNodeVO> topOutflowNodes = boards.stream()
                .filter(b -> b.getNetInflow() != null)
                .sorted(Comparator.comparing(StockIndustryBoard::getNetInflow))
                .limit(5)
                .map(this::toNodeVO)
                .toList();

        summary.setTopInflowSectors(topInflowNodes);
        summary.setTopOutflowSectors(topOutflowNodes);

        return summary;
    }

    private FundFlowGraphNodeVO toNodeVO(StockIndustryBoard b) {
        FundFlowGraphNodeVO vo = new FundFlowGraphNodeVO();
        vo.setId("board_" + b.getSectorName());
        vo.setName(b.getSectorName());
        vo.setCategory("board");
        vo.setChangePercent(b.getChangePercent());
        vo.setNetInflow(b.getNetInflow());
        vo.setTotalAmount(b.getTotalAmount());
        vo.setCode(b.getLeadingStock());
        return vo;
    }

    /**
     * 基于本地 stock_quote 股票实时行情表，统计大盘分析与 15 个高密度精细化涨跌分布区间柱状图数据
     */
    public MarketSentimentVO getMarketSentiment() {
        List<StockQuoteSentimentDTO> quotes = stockQuoteRepository.findAllSentimentQuotes();
        if (CollectionUtils.isEmpty(quotes)) {
            return new MarketSentimentVO();
        }

        MarketSentimentVO vo = new MarketSentimentVO();
        vo.processQuotes(quotes);

        try {
            LocalDateTime maxCreatedAt = stockQuoteRepository.findMaxCreatedAt();
            if (maxCreatedAt != null) {
                vo.setUpdateTime(maxCreatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }
        } catch (Exception e) {
            log.debug("获取最新行情时间失败", e);
        }

        // 构建近5日成交额列表 (单位: 万亿)
        // 日期轴以【个股历史行情】为准：它按全市场逐只同步，是本地最完整的交易日序列。
        // 不能用板块历史的「实际存在的日期」当轴——板块历史盘后回补受上游同花顺发布时间影响，
        // 曾出现「17:45 回补时上游当日数据还没出 → 返回空数组 → 被当成成功静默跳过」导致库里
        // 永久缺一天；若按存在日期当轴，缺口会被悄悄吞掉：图上少一根柱子，且「较昨日」
        // 会跨成隔两个交易日的差值。这里显式按交易日对齐，并对板块缺失日回退个股汇总。
        try {
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE);

            // 盘中/收盘后实时行情已落到今天 → 今日优先用实时全市场成交额
            boolean realtimeToday = false;
            if (isTradingDay(today) && vo.getTotalTurnover() != null
                    && vo.getTotalTurnover().compareTo(BigDecimal.ZERO) > 0) {
                LocalDateTime maxCreatedAt = stockQuoteRepository.findMaxCreatedAt();
                realtimeToday = maxCreatedAt != null && today.equals(maxCreatedAt.toLocalDate());
            }

            List<String> dateAxis = new ArrayList<>(stockQuoteHistoryRepository
                    .findRecentTradeDates(TURNOVER_AXIS_QUERY_DAYS));
            if (realtimeToday && !dateAxis.contains(todayStr)) {
                dateAxis.add(todayStr);
            }
            Collections.sort(dateAxis);
            if (dateAxis.size() > RECENT_TURNOVER_DAYS) {
                dateAxis = dateAxis.subList(dateAxis.size() - RECENT_TURNOVER_DAYS, dateAxis.size());
            }
            if (dateAxis.isEmpty()) {
                log.warn("近5日成交额跳过构建：个股历史行情为空，无法确定交易日序列");
                return vo;
            }

            Map<String, BigDecimal> boardAmountByDate = sumBoardAmountByTradeDates(dateAxis);
            Map<String, BigDecimal> quoteAmountByDate = sumQuoteTurnoverByTradeDates(dateAxis);

            List<DailyTurnoverItem> dailyTurnoverList = new ArrayList<>();
            // 未四舍五入的「万亿」值，仅用于计算「较昨日」，避免 0.01 万亿(100亿)量化误差
            List<BigDecimal> rawTurnovers = new ArrayList<>();
            List<String> fallbackDates = new ArrayList<>();
            DateTimeFormatter mmddFormatter = DateTimeFormatter.ofPattern("MM-dd");

            for (String date : dateAxis) {
                BigDecimal amount = boardAmountByDate.get(date);
                if (amount == null) {
                    amount = quoteAmountByDate.get(date);
                    if (amount != null) {
                        fallbackDates.add(date);
                    }
                }
                if (date.equals(todayStr) && realtimeToday) {
                    amount = vo.getTotalTurnover();
                }
                if (amount == null) {
                    continue;
                }

                BigDecimal amountWanYi = amount.divide(YI_YUAN, 6, RoundingMode.HALF_UP);
                dailyTurnoverList.add(new DailyTurnoverItem(
                        LocalDate.parse(date).format(mmddFormatter),
                        amountWanYi.setScale(2, RoundingMode.HALF_UP),
                        date.equals(todayStr)
                ));
                rawTurnovers.add(amountWanYi);
            }

            if (!fallbackDates.isEmpty()) {
                log.warn("近5日成交额：板块历史缺失 {} 天，已回退个股历史成交额汇总，dates={}",
                        fallbackDates.size(), fallbackDates);
            }

            vo.setRecent5DaysTurnover(dailyTurnoverList);

            // 「较昨日」用真实的前后两个交易日成交额差（尾部两笔即最新与前一交易日）
            if (rawTurnovers.size() >= 2) {
                BigDecimal latest = rawTurnovers.get(rawTurnovers.size() - 1);
                BigDecimal previous = rawTurnovers.get(rawTurnovers.size() - 2);
                if (previous.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal diff = latest.subtract(previous);
                    vo.setTurnoverChangeAmount(diff.multiply(YI_YUAN).setScale(2, RoundingMode.HALF_UP));
                    vo.setVolumeRatio(latest.divide(previous, 2, RoundingMode.HALF_UP));
                    vo.setVolumeChangePercent(diff.divide(previous, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception e) {
            log.warn("构建近5日成交额列表异常", e);
        }

        return vo;
    }

    /** 按交易日汇总板块成交额（各行业板块 amount 之和，单位：元） */
    private Map<String, BigDecimal> sumBoardAmountByTradeDates(List<String> dates) {
        Map<String, BigDecimal> amountByDate = new HashMap<>();
        for (Object[] row : stockIndustryBoardHistoryRepository.sumAmountByTradeDates(dates)) {
            if (row == null || row[0] == null || row[1] == null) {
                continue;
            }
            amountByDate.put(String.valueOf(row[0]), new BigDecimal(String.valueOf(row[1])));
        }
        return amountByDate;
    }

    /** 按交易日汇总个股成交额（各股 turnover 之和，单位：元），板块历史缺口时的兜底 */
    private Map<String, BigDecimal> sumQuoteTurnoverByTradeDates(List<String> dates) {
        Map<String, BigDecimal> amountByDate = new HashMap<>();
        for (Object[] row : stockQuoteHistoryRepository.sumTurnoverByTradeDates(dates)) {
            if (row == null || row[0] == null || row[1] == null) {
                continue;
            }
            amountByDate.put(String.valueOf(row[0]), new BigDecimal(String.valueOf(row[1])));
        }
        return amountByDate;
    }

    /**
     * 判断是否为 A 股交易日：周末与 stock_trade_calendar 中登记的节假日休市
     */
    private boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        try {
            return !stockTradeCalendarRepository.existsByTradeDateAndMarket(date.toString(), "A");
        } catch (Exception e) {
            log.debug("交易日判断失败，按交易日处理", e);
            return true;
        }
    }

}
