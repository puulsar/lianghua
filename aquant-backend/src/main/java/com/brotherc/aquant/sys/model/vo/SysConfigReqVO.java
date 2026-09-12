package com.brotherc.aquant.sys.model.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改系统配置请求
 */
@Data
public class SysConfigReqVO {

    @NotBlank(message = "配置名称不能为空")
    private String name;

    @NotBlank(message = "配置值不能为空")
    private String value;
}