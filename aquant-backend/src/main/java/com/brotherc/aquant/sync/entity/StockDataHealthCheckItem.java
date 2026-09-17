package com.brotherc.aquant.sync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 每日数据体检明细项。
 */
@Data
@Entity
@Table(name = "stock_data_health_check_item", indexes = {
        @Index(name = "idx_health_item_check_id", columnList = "check_id")
})
public class StockDataHealthCheckItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属体检主记录 id */
    @Column(name = "check_id")
    private Long checkId;

    /** 检查项编码，如 DAILY_KLINE_COVERAGE */
    @Column(name = "item_key", length = 64)
    private String itemKey;

    /** 检查项名称 */
    @Column(name = "item_name", length = 64)
    private String itemName;

    /** 结论：PASS / WARN / FAIL */
    @Column(name = "status", length = 16)
    private String status;

    /** 期望值描述 */
    @Column(name = "expected", length = 128)
    private String expected;

    /** 实际值描述 */
    @Column(name = "actual", length = 128)
    private String actual;

    /** 说明/处理建议 */
    @Column(name = "message", length = 512)
    private String message;

    /** 展示顺序 */
    @Column(name = "sort_order")
    private Integer sortOrder;

}
