package com.brotherc.aquant.strategy.controller;

import com.brotherc.aquant.common.constant.StockSyncConstant;
import com.brotherc.aquant.common.model.dto.ResponseDTO;
import com.brotherc.aquant.common.utils.UserContext;
import com.brotherc.aquant.strategy.model.config.StrategyConfig;
import com.brotherc.aquant.strategy.repository.StockStrategyDualMaBacktestSnapshotRepository;
import com.brotherc.aquant.strategy.repository.StockStrategyGridBacktestSnapshotRepository;
import com.brotherc.aquant.strategy.repository.StockStrategyMacdBacktestSnapshotRepository;
import com.brotherc.aquant.strategy.repository.StockStrategyMomentumBacktestSnapshotRepository;
import com.brotherc.aquant.strategy.service.StockStrategySnapshotService;
import com.brotherc.aquant.strategy.service.StrategyConfigService;
import com.brotherc.aquant.sync.entity.StockSync;
import com.brotherc.aquant.sync.repository.StockSyncRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量化策略管理（超级管理员）。对 /admin/strategy 的每次访问强制校验 admin 角色。
 */
@Slf4j
@RestController
@RequestMapping("/admin/strategy")
@RequiredArgsConstructor
@Tag(name = "量化策略管理（后台）")
public class StockStrategyAdminController {

    private final StrategyConfigService strategyConfigService;
    private final StockStrategySnapshotService stockStrategySnapshotService;
    private final StockSyncRepository stockSyncRepository;
    private final StockStrategyDualMaBacktestSnapshotRepository dualMaSnapshotRepository;
    private final StockStrategyMomentumBacktestSnapshotRepository momentumSnapshotRepository;
    private final StockStrategyMacdBacktestSnapshotRepository macdSnapshotRepository;
    private final StockStrategyGridBacktestSnapshotRepository gridSnapshotRepository;

    private void requireAdmin() {
        UserContext.requireAdmin();
    }

    @Operation(summary = "获取量化策略当前配置、快照水位与快照统计")
    @GetMapping("/config")
    public ResponseDTO<Map<String, Object>> config() {
        requireAdmin();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("config", strategyConfigService.getConfig());
        data.put("watermarks", watermarks());
        data.put("stats", stats());
        return ResponseDTO.success(data);
    }

    @Operation(summary = "保存量化策略配置")
    @PostMapping("/config")
    public ResponseDTO<Void> saveConfig(@RequestBody StrategyConfig cfg) {
        requireAdmin();
        strategyConfigService.saveAll(cfg);
        return ResponseDTO.success();
    }

    @Operation(summary = "重新生成回测快照（可选指定策略；缺省全部）")
    @PostMapping("/refresh")
    public ResponseDTO<Map<String, String>> refresh(@RequestBody(required = false) RefreshReq req) {
        requireAdmin();
        String strategy = req == null ? null : req.strategy;
        Map<String, String> result = new LinkedHashMap<>();
        if (strategy == null || strategy.isBlank() || "all".equalsIgnoreCase(strategy)
                || "dualMa".equalsIgnoreCase(strategy)) {
            stockStrategySnapshotService.refreshDualMaBacktestSnapshots();
            result.put("dualMa", "已触发");
        }
        if (strategy == null || strategy.isBlank() || "all".equalsIgnoreCase(strategy)
                || "momentum".equalsIgnoreCase(strategy)) {
            stockStrategySnapshotService.refreshMomentumBacktestSnapshots();
            result.put("momentum", "已触发");
        }
        if (strategy == null || strategy.isBlank() || "all".equalsIgnoreCase(strategy)
                || "macd".equalsIgnoreCase(strategy)) {
            stockStrategySnapshotService.refreshMacdBacktestSnapshots();
            result.put("macd", "已触发");
        }
        if (strategy == null || strategy.isBlank() || "all".equalsIgnoreCase(strategy)
                || "grid".equalsIgnoreCase(strategy)) {
            stockStrategySnapshotService.refreshGridBacktestSnapshots();
            result.put("grid", "已触发");
        }
        return ResponseDTO.success(result);
    }

    // ---------- 快照统计 ----------

    private Map<String, Object> watermarks() {
        Map<String, Object> watermarks = new LinkedHashMap<>();
        putWatermark(watermarks, "dualMa", StockSyncConstant.STOCK_STRATEGY_DUAL_MA_BACKTEST_SNAPSHOT_LATEST);
        putWatermark(watermarks, "momentum", StockSyncConstant.STOCK_STRATEGY_MOMENTUM_BACKTEST_SNAPSHOT_LATEST);
        putWatermark(watermarks, "macd", StockSyncConstant.STOCK_STRATEGY_MACD_BACKTEST_SNAPSHOT_LATEST);
        putWatermark(watermarks, "grid", StockSyncConstant.STOCK_STRATEGY_GRID_BACKTEST_SNAPSHOT_LATEST);
        return watermarks;
    }

    private Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dualMa", statOf(dualMaSnapshot())
                .label(new String[]{"maShort", "maLong", "years"}));
        stats.put("momentum", statOf(momentumSnapshot())
                .label(new String[]{"lookback", "years"}));
        stats.put("macd", statOf(macdSnapshot())
                .label(new String[]{"fast", "slow", "signal", "years"}));
        stats.put("grid", statOf(gridSnapshot())
                .label(new String[]{"rate", "count", "years"}));
        return stats;
    }

    private interface SnapshotAccess {
        Long batchNo();

        long count();

        List<Object[]> reliability();

        List<Object[]> combos();
    }

    private SnapshotAccess dualMaSnapshot() {
        Long batchNo = parseBatchNo(watermark("dualMa"));
        return new SnapshotAccess() {
            public Long batchNo() { return batchNo; }
            public long count() { return batchNo == null ? 0 : dualMaSnapshotRepository.countByBatchNo(batchNo); }
            public List<Object[]> reliability() { return batchNo == null ? List.of() : dualMaSnapshotRepository.reliabilityDistribution(batchNo); }
            public List<Object[]> combos() { return batchNo == null ? List.of() : dualMaSnapshotRepository.topCombos(batchNo); }
        };
    }

    private SnapshotAccess momentumSnapshot() {
        Long batchNo = parseBatchNo(watermark("momentum"));
        return new SnapshotAccess() {
            public Long batchNo() { return batchNo; }
            public long count() { return batchNo == null ? 0 : momentumSnapshotRepository.countByBatchNo(batchNo); }
            public List<Object[]> reliability() { return batchNo == null ? List.of() : momentumSnapshotRepository.reliabilityDistribution(batchNo); }
            public List<Object[]> combos() { return batchNo == null ? List.of() : momentumSnapshotRepository.topCombos(batchNo); }
        };
    }

    private SnapshotAccess macdSnapshot() {
        Long batchNo = parseBatchNo(watermark("macd"));
        return new SnapshotAccess() {
            public Long batchNo() { return batchNo; }
            public long count() { return batchNo == null ? 0 : macdSnapshotRepository.countByBatchNo(batchNo); }
            public List<Object[]> reliability() { return batchNo == null ? List.of() : macdSnapshotRepository.reliabilityDistribution(batchNo); }
            public List<Object[]> combos() { return batchNo == null ? List.of() : macdSnapshotRepository.topCombos(batchNo); }
        };
    }

    private SnapshotAccess gridSnapshot() {
        Long batchNo = parseBatchNo(watermark("grid"));
        return new SnapshotAccess() {
            public Long batchNo() { return batchNo; }
            public long count() { return batchNo == null ? 0 : gridSnapshotRepository.countByBatchNo(batchNo); }
            public List<Object[]> reliability() { return batchNo == null ? List.of() : gridSnapshotRepository.reliabilityDistribution(batchNo); }
            public List<Object[]> combos() { return batchNo == null ? List.of() : gridSnapshotRepository.topCombos(batchNo); }
        };
    }

    private Builder statOf(SnapshotAccess snap) {
        return new Builder(snap);
    }

    private static class Builder {
        private final SnapshotAccess snap;
        private String[] labels;

        Builder(SnapshotAccess snap) {
            this.snap = snap;
        }

        Builder label(String[] labels) {
            this.labels = labels;
            return this;
        }

        Map<String, Object> build() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("batchNo", snap.batchNo());
            s.put("total", snap.count());
            s.put("reliability", reliabilityMap(snap.reliability()));
            s.put("topCombos", combos(snap.combos(), labels));
            return s;
        }
    }

    private static Map<String, Object> reliabilityMap(List<Object[]> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Object[] r : rows) {
            m.put(String.valueOf(r[0]), toLong(r[1]));
        }
        return m;
    }

    private static List<Map<String, Object>> combos(List<Object[]> rows, String[] labels) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (labels == null) {
            return out;
        }
        for (Object[] r : rows) {
            Map<String, Object> combo = new LinkedHashMap<>();
            // label columns first, then cnt / avgRet / avgWin
            for (int i = 0; i < labels.length; i++) {
                combo.put(labels[i], r[i]);
            }
            combo.put("count", toLong(r[labels.length]));
            combo.put("avgReturn", r[labels.length + 1]);
            combo.put("avgWin", r[labels.length + 2]);
            out.add(combo);
        }
        return out;
    }

    private String watermark(String key) {
        return watermarks().get(key) == null ? null : String.valueOf(watermarks().get(key));
    }

    private void putWatermark(Map<String, Object> watermarks, String key, String syncName) {
        StockSync sync = stockSyncRepository.findByName(syncName);
        watermarks.put(key, sync == null || sync.getValue() == null ? null : sync.getValue());
    }

    private Long parseBatchNo(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    public static class RefreshReq {
        public String strategy;
    }
}