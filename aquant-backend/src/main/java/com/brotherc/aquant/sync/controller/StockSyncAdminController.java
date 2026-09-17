package com.brotherc.aquant.sync.controller;

import com.brotherc.aquant.common.exception.BusinessException;
import com.brotherc.aquant.common.exception.ExceptionEnum;
import com.brotherc.aquant.common.model.dto.ResponseDTO;
import com.brotherc.aquant.common.utils.UserContext;
import com.brotherc.aquant.sync.entity.StockSync;
import com.brotherc.aquant.sync.model.vo.DataHealthCheckVO;
import com.brotherc.aquant.sync.model.vo.StockSyncWatermarkReqVO;
import com.brotherc.aquant.sync.repository.StockSyncRepository;
import com.brotherc.aquant.sync.service.StockDataHealthCheckService;
import com.brotherc.aquant.task.StockDailyCloseTask;
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

import java.time.LocalDate;
import java.util.List;

@Validated
@Tag(name = "数据同步管理（后台）")
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class StockSyncAdminController {

    private final StockSyncRepository stockSyncRepository;
    private final StockSyncTask stockSyncTask;
    private final StockDailyCloseTask stockDailyCloseTask;
    private final StockDataHealthCheckService stockDataHealthCheckService;

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

    @Operation(summary = "手动触发收盘强制刷新（含结果校验与失败重试）")
    @PostMapping("/close-refresh")
    public ResponseDTO<String> closeRefresh() {
        requireLogin();
        boolean ok = stockSyncTask.triggerCloseSnapshotRefresh();
        return ok
                ? ResponseDTO.success("收盘强制刷新成功（已校验数据落库）")
                : ResponseDTO.success("收盘强制刷新未成功（非交易日，或多次重试后仍失败，详见后端日志）");
    }

    @Operation(summary = "手动触发收盘数据收口（收盘快照+日K/板块历史+估值+策略快照，完成后自动体检）")
    @PostMapping("/daily-close")
    public ResponseDTO<String> dailyClose() {
        requireLogin();
        stockDailyCloseTask.triggerDailyCloseJobAsync();
        return ResponseDTO.success("收盘数据收口作业已开始，请在日志观察进度；完成后会自动写入一条体检记录");
    }

    @Operation(summary = "手动触发一次当日数据体检")
    @PostMapping("/health-check")
    public ResponseDTO<DataHealthCheckVO> healthCheck() {
        requireLogin();
        LocalDate tradeDate = LocalDate.now();
        DataHealthCheckVO vo = stockDataHealthCheckService.runCheck(tradeDate,
                StockDataHealthCheckService.TRIGGER_MANUAL);
        return ResponseDTO.success(vo);
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