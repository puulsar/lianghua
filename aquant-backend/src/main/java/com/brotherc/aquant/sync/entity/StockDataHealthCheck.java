package com.brotherc.aquant.sync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 每日数据体检主记录。
 * <p>
 * 收盘收口作业跑完后自动执行一次体检，把结果落库，供前端「数据体检」页面读取。
 */
@Data
@Entity
@Table(name = "stock_data_health_check", indexes = {
        @Index(name = "idx_health_check_trade_date", columnList = "trade_date"),
        @Index(name = "idx_health_check_time", columnList = "check_time")
})
public class StockDataHealthCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被体检的交易日 yyyy-MM-dd */
    @Column(name = "trade_date", length = 10)
    private String tradeDate;

    /** 体检执行时间 */
    @Column(name = "check_time")
    private LocalDateTime checkTime;

    /** 总体结论：PASS / WARN / FAIL */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "pass_count")
    private Integer passCount;

    @Column(name = "warn_count")
    private Integer warnCount;

    @Column(name = "fail_count")
    private Integer failCount;

    /** 体检耗时（毫秒） */
    @Column(name = "duration_millis")
    private Long durationMillis;

    /** 触发方式：SCHEDULED（收盘作业自动） / MANUAL（手动） */
    @Column(name = "trigger_type", length = 16)
    private String triggerType;

    /** 一句话结论，便于前端直接展示 */
    @Column(name = "summary", length = 512)
    private String summary;

}
