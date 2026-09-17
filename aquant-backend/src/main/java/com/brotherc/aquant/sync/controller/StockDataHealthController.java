package com.brotherc.aquant.sync.controller;

import com.brotherc.aquant.common.model.dto.ResponseDTO;
import com.brotherc.aquant.sync.model.vo.DataHealthCheckVO;
import com.brotherc.aquant.sync.service.StockDataHealthCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 每日数据体检结果查询（只读，供前端「数据体检」页面展示）。
 */
@Validated
@Tag(name = "数据体检")
@RestController
@RequiredArgsConstructor
@RequestMapping("/dataHealth")
public class StockDataHealthController {

    private final StockDataHealthCheckService stockDataHealthCheckService;

    @Operation(summary = "查询最近一次数据体检结果（含明细项）")
    @GetMapping("/latest")
    public ResponseDTO<DataHealthCheckVO> getLatest() {
        return ResponseDTO.success(stockDataHealthCheckService.getLatest());
    }

    @Operation(summary = "查询最近若干次数据体检结果（不含明细，用于历史趋势）")
    @GetMapping("/recent")
    public ResponseDTO<List<DataHealthCheckVO>> getRecent(
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        return ResponseDTO.success(stockDataHealthCheckService.getRecent(limit));
    }

}
