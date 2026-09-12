package com.brotherc.aquant.sync.controller;

import com.brotherc.aquant.common.exception.BusinessException;
import com.brotherc.aquant.common.exception.ExceptionEnum;
import com.brotherc.aquant.common.model.dto.ResponseDTO;
import com.brotherc.aquant.common.utils.UserContext;
import com.brotherc.aquant.sync.entity.StockSync;
import com.brotherc.aquant.sync.model.vo.StockSyncWatermarkReqVO;
import com.brotherc.aquant.sync.repository.StockSyncRepository;
import com.brotherc.aquant.task.StockSyncTask;
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

@Validated
@Tag(name = "数据同步管理（后台）")
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class StockSyncAdminController {

    private final StockSyncRepository stockSyncRepository;
    private final StockSyncTask stockSyncTask;

    private void requireLogin() {
        UserContext.requireAdmin();
    }

    @Operation(summary = "列出全部同步水位")
    @GetMapping("/watermarks")
    public ResponseDTO<List<StockSync>> listWatermarks() {
        requireLogin();
        return ResponseDTO.success(stockSyncRepository.findAll());
    }

    @Operation(summary = "手动触发全量/增量同步")
    @PostMapping("/trigger")
    public ResponseDTO<String> triggerSync() {
        requireLogin();
        boolean started = stockSyncTask.runFullSync();
        return started
                ? ResponseDTO.success("同步已开始，请在日志观察进度")
                : ResponseDTO.success("已有同步任务在执行中，本次跳过");
    }

    @Operation(summary = "修改某个同步水位值")
    @PostMapping("/watermark")
    public ResponseDTO<Void> updateWatermark(@RequestBody @Valid StockSyncWatermarkReqVO reqVO) {
        requireLogin();
        validateNumeric(reqVO.getValue());

        StockSync stockSync = stockSyncRepository.findByName(reqVO.getName());
        if (stockSync == null) {
            throw new BusinessException(ExceptionEnum.SYS_CHECK_ERROR, "未找到名为 " + reqVO.getName() + " 的同步水位");
        }
        stockSync.setValue(reqVO.getValue());
        stockSyncRepository.save(stockSync);
        return ResponseDTO.success();
    }

    private void validateNumeric(String value) {
        try {
            Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ExceptionEnum.SYS_CHECK_ERROR, "水位值必须为数字（毫秒时间戳）");
        }
    }
}