package com.brotherc.aquant.sync.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 每日数据体检明细项。
 */
@Data
@Schema(description = "数据体检明细项")
public class DataHealthCheckItemVO {

    @Schema(description = "检查项编码")
    private String itemKey;

    @Schema(description = "检查项名称")
    private String itemName;

    @Schema(description = "结论：PASS / WARN / FAIL")
    private String status;

    @Schema(description = "期望值")
    private String expected;

    @Schema(description = "实际值")
    private String actual;

    @Schema(description = "说明")
    private String message;

    @Schema(description = "展示顺序")
    private Integer sortOrder;

}
