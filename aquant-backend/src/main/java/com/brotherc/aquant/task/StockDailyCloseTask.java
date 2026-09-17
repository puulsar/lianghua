package com.brotherc.aquant.task;

import com.brotherc.aquant.common.utils.StockHelper;
import com.brotherc.aquant.indicator.service.StockDupontAnalysisService;
import com.brotherc.aquant.indicator.service.StockGrowthMetricsService;
import com.brotherc.aquant.indicator.service.StockValuationMetricsService;
import com.brotherc.aquant.strategy.service.StockStrategySnapshotService;
import com.brotherc.aquant.sync.service.StockDataHealthCheckService;
import com.brotherc.aquant.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 收盘后「当日数据收口」作业。
 * <p>
 * 背景：15:01 的收盘定点刷新（{@link StockSyncTask#scheduledCloseSnapshotRefresh()}）只保证
 * stock_quote / 指数 / 板块的<b>实时快照</b>落到当天，刻意跳过了历史回补；而历史日线回补
 * （stock_quote_history）、板块历史 K 线、估值/成长性、策略快照只在应用启动时跑一次。
 * 结果是：收盘后前端能看到当天涨跌，但一切按「日 K / 历史序列」取数的功能仍是昨天的。
 * <p>
 * 这个作业就是补上这一环：每个交易日收盘后固定把当日所有数据收口，并<b>立即做一次数据体检</b>，
 * 把「跑完了」变成「跑完了且确认数据是对的」。
 * <p>
 * 时间选择：15:40 而不是 15:00——同花顺板块指数、东财个股日线在收盘后都有发布时间延迟，
 * 15:01 时上游经常返回「200 + 空数组」，提前跑只会白跑一轮并留下静默缺口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDailyCloseTask {

    private final StockHelper stockHelper;
    private final StockSyncTask stockSyncTask;
    private final StockValuationMetricsService stockValuationMetricsService;
    private final StockDupontAnalysisService stockDupontAnalysisService;
    private final StockGrowthMetricsService stockGrowthMetricsService;
    private final StockStrategySnapshotService stockStrategySnapshotService;
    private final StockDataHealthCheckService stockDataHealthCheckService;
    private final SysConfigService sysConfigService;

    /** 收盘收口作业并发锁：防止与手动触发重叠 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 等待启动全量同步的最长时间（秒） */
    private static final int FULL_SYNC_WAIT_SECONDS = 30 * 60;
    /** 等待启动全量同步时的轮询间隔（秒） */
    private static final int FULL_SYNC_POLL_INTERVAL_SECONDS = 15;

    /**
     * 每个交易日 15:40 的收盘数据收口：刷新收盘快照 → 补当日日 K / 板块历史 → 刷新估值等派生指标
     * → 刷新策略快照 → 数据体检。
     * <p>
     * 用 @Async 是因为这一轮短则几分钟、长则一两个小时（板块历史是逐板块 HTTP），
     * 不能占用调度线程，否则会堵住每 5 分钟的盘中增量同步。
     */
    @Async
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void scheduledDailyCloseJob() {
        if (!sysConfigService.getBoolean(SysConfigService.AUTO_SYNC)) {
            log.info("自动同步已关闭，跳过收盘数据收口作业");
            return;
        }
        LocalDate today = LocalDate.now();
        if (!stockHelper.isTradeDay(today)) {
            log.info("今天非交易日，跳过收盘数据收口作业, date={}", today);
            return;
        }
        runDailyCloseJob();
    }

    /**
     * 手动触发收盘数据收口（后台接口 /admin/sync/daily-close 调用）。
     * <p>
     * 异步执行并立即返回：这一轮短则几分钟、长则一两个小时（板块历史是逐板块 HTTP），
     * 不能让 HTTP 请求一直挂着；进度与结果看后端日志，收口完成后会自动落一条体检记录。
     */
    @Async
    public void triggerDailyCloseJobAsync() {
        runDailyCloseJob();
    }

    /** 同步执行一次收盘数据收口（调度与手动触发共用，带并发锁） */
    public void runDailyCloseJob() {
        if (!running.compareAndSet(false, true)) {
            log.info("收盘数据收口作业已在执行中，本次跳过");
            return;
        }
        try {
            execute();
        } finally {
            running.set(false);
        }
    }

    private void execute() {
        // 与启动全量同步互斥：并行跑会让策略快照事务互相标成 rollback-only（实测 MACD 快照因此失败）
        if (!waitForFullSync()) {
            log.warn("全量同步仍在运行，等待超时，本次收盘收口继续但快照步骤可能失败");
        }
        LocalDateTime now = LocalDateTime.now();
        log.info("====== 收盘数据收口作业开始, syncTime={} ======", now);
        long start = System.currentTimeMillis();

        // 1. 收盘快照强制刷新（含校验与重试）：行情 / 指数 / 板块实时快照
        runStep("收盘快照强制刷新", () -> stockSyncTask.triggerCloseSnapshotRefresh());

        // 2. 当日日 K 线落库：收盘后再跑一次完整同步，这次不跳过历史回补
        runStep("个股日K线回补", () -> stockSyncTask.syncStackQuote(LocalDateTime.now(), false, true));
        runStep("指数历史与实时", () -> stockSyncTask.syncStockIndex(LocalDateTime.now(), true));
        runStep("板块历史K线回补", () -> stockSyncTask.syncStockBoard(LocalDateTime.now(), false, true));

        // 3. 派生指标：估值 / 杜邦 / 成长性（依赖当日收盘价）
        runStep("估值指标刷新", stockValuationMetricsService::refreshValuationMetrics);
        runStep("杜邦分析刷新", stockDupontAnalysisService::refreshDupontAnalysis);
        runStep("成长性指标刷新", stockGrowthMetricsService::refreshGrowthMetrics);

        // 4. 策略快照：让第二天的策略回测直接命中快照，不用全市场重算
        runStepWithRetry("双均线策略快照", stockStrategySnapshotService::refreshDualMaBacktestSnapshots);
        runStepWithRetry("动量策略快照", stockStrategySnapshotService::refreshMomentumBacktestSnapshots);
        runStepWithRetry("MACD策略快照", stockStrategySnapshotService::refreshMacdBacktestSnapshots);
        runStepWithRetry("网格策略快照", stockStrategySnapshotService::refreshGridBacktestSnapshots);

        long costSeconds = (System.currentTimeMillis() - start) / 1000;
        log.info("====== 收盘数据收口作业结束, 耗时 {}s ======", costSeconds);

        // 5. 收口完成立即体检：确认数据真的齐了，而不是「没报错」
        try {
            stockDataHealthCheckService.runCheck(stockHelper.latestClosedTradeDay(now),
                    StockDataHealthCheckService.TRIGGER_SCHEDULED);
        } catch (Exception e) {
            log.error("收盘数据体检失败", e);
        }
    }

    /**
     * 等待启动全量同步结束，避免两者并行刷新策略快照。
     *
     * @return true 表示全量同步已结束（可以安全开始）；false 表示等待超时
     */
    private boolean waitForFullSync() {
        int waitedSeconds = 0;
        while (stockSyncTask.isFullSyncRunning()) {
            if (waitedSeconds >= FULL_SYNC_WAIT_SECONDS) {
                return false;
            }
            if (waitedSeconds == 0) {
                log.info("检测到启动全量同步仍在运行，收盘收口作业等待其结束后再开始");
            }
            try {
                Thread.sleep(FULL_SYNC_POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            waitedSeconds += FULL_SYNC_POLL_INTERVAL_SECONDS;
        }
        if (waitedSeconds > 0) {
            log.info("全量同步已结束，等待 {}s 后开始收盘收口作业", waitedSeconds);
        }
        return true;
    }

    /**
     * 单步执行并兜住异常：收口作业是长链路，任何一步失败都不能让后面的步骤全部跳过。
     * 注意这里只 catch Exception（OOM 之类 Error 仍然上抛，避免带着坏状态继续跑）。
     */
    private void runStep(String stepName, Runnable action) {
        long start = System.currentTimeMillis();
        try {
            action.run();
            log.info("收盘收口[{}]完成, 耗时 {}s", stepName, (System.currentTimeMillis() - start) / 1000);
        } catch (Exception e) {
            log.error("收盘收口[{}]失败, 耗时 {}s", stepName, (System.currentTimeMillis() - start) / 1000, e);
        }
    }

    /**
     * 策略快照专用：失败后重试一次。
     * <p>
     * 快照刷新是覆盖全市场的大事务，偶尔因并发（与启动全量同步撞车）失败；
     * 快照写不进去会让第二天策略回测退化为全市场重算（约 30s），因此值得重试一次。
     */
    private void runStepWithRetry(String stepName, Runnable action) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            long start = System.currentTimeMillis();
            try {
                action.run();
                log.info("收盘收口[{}]完成, 耗时 {}s", stepName, (System.currentTimeMillis() - start) / 1000);
                return;
            } catch (Exception e) {
                log.warn("收盘收口[{}]第 {} 次失败, 耗时 {}s", stepName, attempt,
                        (System.currentTimeMillis() - start) / 1000, e);
                if (attempt < 2) {
                    try {
                        Thread.sleep(20_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        log.error("收盘收口[{}]两次尝试均失败，本次快照未刷新", stepName);
    }

}
