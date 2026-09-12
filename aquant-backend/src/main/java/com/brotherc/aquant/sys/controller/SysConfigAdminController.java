package com.brotherc.aquant.sys.controller;

import com.brotherc.aquant.common.model.dto.ResponseDTO;
import com.brotherc.aquant.common.utils.UserContext;
import com.brotherc.aquant.sys.entity.SysConfig;
import com.brotherc.aquant.sys.model.vo.SysConfigReqVO;
import com.brotherc.aquant.sys.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统开关/设置管理（需登录）。路径于 JwtAuthFilter 白名单之外，强制鉴权。
 */
@Validated
@Tag(name = "系统开关设置（后台）")
@RestController
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class SysConfigAdminController {

    private final SysConfigService sysConfigService;

    private void requireLogin() {
        UserContext.requireAdmin();
    }

    @Operation(summary = "列出全部系统配置（开关）")
    @GetMapping
    public ResponseDTO<List<SysConfig>> list() {
        requireLogin();
        return ResponseDTO.success(sysConfigService.findAll());
    }

    @Operation(summary = "更新某个系统配置（开关）")
    @PostMapping
    public ResponseDTO<Void> update(@RequestBody @Valid SysConfigReqVO reqVO) {
        requireLogin();
        sysConfigService.setValue(reqVO.getName(), reqVO.getValue());
        return ResponseDTO.success();
    }
}