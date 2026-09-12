package com.brotherc.aquant.sys.service;

import com.brotherc.aquant.sys.entity.SysConfig;
import com.brotherc.aquant.sys.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置（运行时开关）服务。
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    /** 接口文档（Swagger/Knife4j）访问 */
    public static final String DOCS_ENABLED = "docs.enabled";
    /** Druid 监控访问 */
    public static final String DRUID_ENABLED = "druid.enabled";
    /** 数据自动同步（启动自动同步） */
    public static final String AUTO_SYNC = "sync.autoScheduled";
    /** 预警通知扫描 */
    public static final String NOTIFICATION_ENABLED = "notification.enabled";
    /** 调试日志（DEBUG） */
    public static final String LOGGING_DEBUG = "logging.debug";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(DOCS_ENABLED, "1");
        DEFAULTS.put(DRUID_ENABLED, "1");
        DEFAULTS.put(AUTO_SYNC, "1");
        DEFAULTS.put(NOTIFICATION_ENABLED, "1");
        DEFAULTS.put(LOGGING_DEBUG, "0");
    }

    private final SysConfigRepository sysConfigRepository;

    @PostConstruct
    public void initDefaults() {
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String name = entry.getKey();
            if (sysConfigRepository.findByName(name) == null) {
                SysConfig cfg = new SysConfig();
                cfg.setName(name);
                cfg.setValue(entry.getValue());
                sysConfigRepository.save(cfg);
            }
        }
    }

    public boolean getBoolean(String name) {
        SysConfig cfg = sysConfigRepository.findByName(name);
        if (cfg == null) {
            return toBoolean(DEFAULTS.getOrDefault(name, "1"));
        }
        return toBoolean(cfg.getValue());
    }

    public List<SysConfig> findAll() {
        return sysConfigRepository.findAll();
    }

    @Transactional
    public SysConfig setValue(String name, String value) {
        SysConfig cfg = sysConfigRepository.findByName(name);
        if (cfg == null) {
            cfg = new SysConfig();
            cfg.setName(name);
        }
        cfg.setValue(value);
        sysConfigRepository.save(cfg);

        // 调试日志开关的副作用：动态调整业务日志级别
        if (LOGGING_DEBUG.equals(name)) {
            refreshLoggingLevel();
        }
        return cfg;
    }

    /**
     * 根据当前 logging.debug 设置业务日志级别。
     */
    public void refreshLoggingLevel() {
        try {
            LoggingSystem loggingSystem = LoggingSystem.get(ClassUtils.getDefaultClassLoader());
            LogLevel level = getBoolean(LOGGING_DEBUG) ? LogLevel.DEBUG : LogLevel.INFO;
            loggingSystem.setLogLevel("com.brotherc", level);
        } catch (Exception ignored) {
            // 日志系统不可用时静默忽略
        }
    }

    private boolean toBoolean(String value) {
        return value != null && ("1".equals(value.trim()) || "true".equalsIgnoreCase(value.trim()));
    }
}