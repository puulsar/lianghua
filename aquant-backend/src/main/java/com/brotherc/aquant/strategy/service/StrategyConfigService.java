package com.brotherc.aquant.strategy.service;

import com.brotherc.aquant.common.exception.ExceptionEnum;
import com.brotherc.aquant.strategy.model.config.StrategyConfig;
import com.brotherc.aquant.sys.entity.SysConfig;
import com.brotherc.aquant.sys.repository.SysConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 量化策略参数配置服务。
 * <p>
 * 每个策略一段 JSON 存到 sys_config 的某个 name 下，读取时带默认值兜底，
 * 保存时做范围校验。改动配置后需重新生成回测快照才会有新参数结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyConfigService {

    public static final String KEY_DUAL_MA = "strategy.dualMa";
    public static final String KEY_MOMENTUM = "strategy.momentum";
    public static final String KEY_MACD = "strategy.macd";
    public static final String KEY_GRID = "strategy.grid";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 各参数合法区间 */
    private static final int MA_MIN = 1;
    private static final int MA_MAX = 250;
    private static final int YEAR_MIN = 1;
    private static final int YEAR_MAX = 10;
    private static final int GRID_RATE_MAX_PERCENT = 49; // < 50%
    private static final int GRID_COUNT_MIN = 1;
    private static final int GRID_COUNT_MAX = 50;

    private final SysConfigRepository sysConfigRepository;

    // ---------- 读取 ----------

    public StrategyConfig getConfig() {
        StrategyConfig c = new StrategyConfig();
        c.setDualMa(getDualMa());
        c.setMomentum(getMomentum());
        c.setMacd(getMacd());
        c.setGrid(getGrid());
        return c;
    }

    public StrategyConfig.DualMa getDualMa() {
        return parse(KEY_DUAL_MA, StrategyConfig.DualMa.class, defaultDualMa());
    }

    public StrategyConfig.Momentum getMomentum() {
        return parse(KEY_MOMENTUM, StrategyConfig.Momentum.class, defaultMomentum());
    }

    public StrategyConfig.Macd getMacd() {
        return parse(KEY_MACD, StrategyConfig.Macd.class, defaultMacd());
    }

    public StrategyConfig.Grid getGrid() {
        return parse(KEY_GRID, StrategyConfig.Grid.class, defaultGrid());
    }

    // ---------- 保存 ----------

    @Transactional(rollbackFor = Exception.class)
    public void saveAll(StrategyConfig cfg) {
        if (cfg == null) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        validate(cfg);
        write(KEY_DUAL_MA, cfg.getDualMa());
        write(KEY_MOMENTUM, cfg.getMomentum());
        write(KEY_MACD, cfg.getMacd());
        write(KEY_GRID, cfg.getGrid());
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveDualMa(StrategyConfig.DualMa cfg) {
        validateMaList("maShort", cfg.getMaShort());
        validateMaList("maLong", cfg.getMaLong());
        validateYears(cfg.getYears());
        write(KEY_DUAL_MA, cfg);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveMomentum(StrategyConfig.Momentum cfg) {
        validateLookbackList(cfg.getLookback());
        validateYears(cfg.getYears());
        write(KEY_MOMENTUM, cfg);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveMacd(StrategyConfig.Macd cfg) {
        validateMacd(cfg.getFast(), cfg.getSlow(), cfg.getSignal());
        validateYears(cfg.getYears());
        write(KEY_MACD, cfg);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveGrid(StrategyConfig.Grid cfg) {
        validateGrid(cfg.getRate(), cfg.getCount());
        validateYears(cfg.getYears());
        write(KEY_GRID, cfg);
    }

    // ---------- 校验 ----------

    private void validate(StrategyConfig cfg) {
        validateMaList("maShort", cfg.getDualMa() == null ? null : cfg.getDualMa().getMaShort());
        validateMaList("maLong", cfg.getDualMa() == null ? null : cfg.getDualMa().getMaLong());
        validateYears(cfg.getDualMa() == null ? null : cfg.getDualMa().getYears());
        validateLookbackList(cfg.getMomentum() == null ? null : cfg.getMomentum().getLookback());
        validateYears(cfg.getMomentum() == null ? null : cfg.getMomentum().getYears());
        if (cfg.getMacd() != null) {
            validateMacd(cfg.getMacd().getFast(), cfg.getMacd().getSlow(), cfg.getMacd().getSignal());
            validateYears(cfg.getMacd().getYears());
        }
        if (cfg.getGrid() != null) {
            validateGrid(cfg.getGrid().getRate(), cfg.getGrid().getCount());
            validateYears(cfg.getGrid().getYears());
        }
    }

    private void validateMaList(String name, List<Integer> values) {
        List<Integer> list = normalizeList(values);
        if (list.isEmpty()) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        for (Integer v : list) {
            if (v == null || v < MA_MIN || v > MA_MAX) {
                throw ExceptionEnum.SYS_CHECK_ERROR.toException();
            }
        }
    }

    private void validateLookbackList(List<Integer> values) {
        List<Integer> list = normalizeList(values);
        if (list.isEmpty()) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        for (Integer v : list) {
            if (v == null || v < MA_MIN || v > MA_MAX) {
                throw ExceptionEnum.SYS_CHECK_ERROR.toException();
            }
        }
    }

    private void validateYears(List<Integer> values) {
        List<Integer> list = normalizeList(values);
        if (list.isEmpty()) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        for (Integer v : list) {
            if (v == null || v < YEAR_MIN || v > YEAR_MAX) {
                throw ExceptionEnum.SYS_CHECK_ERROR.toException();
            }
        }
    }

    private void validateMacd(int fast, int slow, int signal) {
        if (fast <= 0 || slow <= 0 || signal <= 0 || fast >= slow) {
            throw ExceptionEnum.STOCK_STRATEGY_MACD_PARAMS_ILLEGAL.toException();
        }
    }

    private void validateGrid(int rate, int count) {
        if (rate <= 0 || rate > GRID_RATE_MAX_PERCENT || count < GRID_COUNT_MIN || count > GRID_COUNT_MAX) {
            throw ExceptionEnum.STOCK_STRATEGY_GRID_PARAMS_ILLEGAL.toException();
        }
    }

    // ---------- 存储 ----------

    private void write(String name, Object value) {
        try {
            SysConfig cfg = sysConfigRepository.findByName(name);
            if (cfg == null) {
                cfg = new SysConfig();
                cfg.setName(name);
            }
            cfg.setValue(MAPPER.writeValueAsString(value));
            sysConfigRepository.save(cfg);
        } catch (JsonProcessingException e) {
            log.error("策略配置序列化失败, name={}", name, e);
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T parse(String name, Class<T> clazz, T def) {
        SysConfig cfg = sysConfigRepository.findByName(name);
        if (cfg == null || StringUtils.isBlank(cfg.getValue())) {
            return def;
        }
        try {
            return MAPPER.readValue(cfg.getValue(), clazz);
        } catch (JsonProcessingException e) {
            log.warn("策略配置解析失败, name={}, 使用默认值", name, e);
            return def;
        }
    }

    // ---------- 默认值 ----------

    private StrategyConfig.DualMa defaultDualMa() {
        return StrategyConfig.DualMa.of(
                List.of(5, 10, 20, 30, 60, 120),
                List.of(5, 10, 20, 30, 60, 120),
                List.of(1, 2, 3, 5)
        );
    }

    private StrategyConfig.Momentum defaultMomentum() {
        return StrategyConfig.Momentum.of(
                List.of(10, 20, 60, 120),
                List.of(1, 2, 3, 5)
        );
    }

    private StrategyConfig.Macd defaultMacd() {
        return StrategyConfig.Macd.of(12, 26, 9, List.of(1, 2, 3, 5));
    }

    private StrategyConfig.Grid defaultGrid() {
        return StrategyConfig.Grid.of(3, 5, List.of(1, 2, 3, 5));
    }

    private List<Integer> normalizeList(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Integer v : values) {
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }
}