package com.brotherc.aquant.sync.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 每日数据体检结果（主记录 + 明细项）。
 */
@Data
@Schema(description = "每日数据体检结果")
public class DataHealthCheckVO {

    @Schema(description = "体检记录 id")
    private Long id;

    @Schema(description = "被体检的交易日")
    private String tradeDate;

    @Schema(description = "体检执行时间")
    private LocalDateTime checkTime;

    @Schema(description = "总体结论：PASS / WARN / FAIL")
    private String status;

    @Schema(description = "通过项数量")
    private Integer passCount;

    @Schema(description = "告警项数量")
    private Integer warnCount;

    @Schema(description = "失败项数量")
    private Integer failCount;

    @Schema(description = "体检耗时（毫秒）")
    private Long durationMillis;

    @Schema(description = "触发方式：SCHEDULED / MANUAL")
    private String triggerType;

    @Schema(description = "一句话结论")
    private String summary;

    @Schema(description = "明细检查项")
    private List<DataHealthCheckItemVO> items;

}
