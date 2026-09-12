package com.brotherc.aquant.sync.model.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改同步水位请求
 */
@Data
public class StockSyncWatermarkReqVO {

    @NotBlank(message = "同步名称不能为空")
    private String name;

    @NotBlank(message = "水位值不能为空")
    private String value;
}