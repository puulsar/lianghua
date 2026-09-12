package com.brotherc.aquant.fund.service;

import com.brotherc.aquant.common.constant.FundPurchaseLimitConstant;
import com.brotherc.aquant.common.constant.StockSyncConstant;
import com.brotherc.aquant.common.utils.StockUtils;
import com.brotherc.aquant.fund.model.dto.FundPurchaseLimitRule;
import com.brotherc.aquant.integration.akshare.model.FundPurchaseEm;
import com.brotherc.aquant.integration.akshare.service.AKShareFundService;
import com.brotherc.aquant.sync.entity.StockSync;
import com.brotherc.aquant.sync.repository.StockSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 全市场开放式基金申购状态/限额同步（数据源：天天基金 fund_purchase_em 全量列表）。
 * <p>
 * 与各公司官网的纳指100额度明细不同，本数据源覆盖全市场开放式基金，为每个基金写入一条
 * “申购”规则（申购状态 + 日累计限额），使「官方渠道额度明细」对所有基金都有数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EMFundPurchaseLimitSyncService implements FundPurchaseLimitSyncService {

    private final AKShareFundService aKShareFundService;
    private final StockFundPurchaseLimitService stockFundPurchaseLimitService;
    private final StockSyncRepository stockSyncRepository;

    @Override
    public String getSourceName() {
        return FundPurchaseLimitConstant.SOURCE_EM_FUND_NAME;
    }

    /**
     * 每天同步一次全市场基金申购状态。
     */
    @Override
    public void sync(LocalDateTime syncTime) {
        StockSync stockSync = stockSyncRepository.findByName(StockSyncConstant.STOCK_EM_FUND_PURCHASE_LIMIT_LATEST);
        Long lastTimestamp = StockUtils.parseSyncTimestamp(stockSync);
        if (lastTimestamp != null) {
            LocalDate lastSyncDate = Instant.ofEpochMilli(lastTimestamp)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            if (lastSyncDate.equals(syncTime.toLocalDate())) {
                log.info("全市场基金申购状态当天已同步，跳过本次同步，syncDate={}", lastSyncDate);
                return;
            }
        }

        List<FundPurchaseEm> purchases;
        try {
            purchases = aKShareFundService.fundPurchaseEm();
        } catch (Exception e) {
            log.error("获取全市场基金申购状态失败，本次不更新额度和同步水位", e);
            return;
        }
        if (CollectionUtils.isEmpty(purchases)) {
            log.warn("全市场基金申购状态列表为空，跳过本次同步");
            return;
        }

        List<FundPurchaseLimitRule> rules = new ArrayList<>();
        for (FundPurchaseEm item : purchases) {
            if (item == null || StringUtils.isBlank(item.getFundCode())) {
                continue;
            }
            FundPurchaseLimitRule rule = new FundPurchaseLimitRule();
            rule.setFundCode(item.getFundCode());
            rule.setCurrency("CNY");
            rule.setSalesChannel(FundPurchaseLimitConstant.CHANNEL_ALL);
            rule.setBusinessType(FundPurchaseLimitConstant.BUSINESS_PURCHASE);
            rule.setStatus(resolveStatus(item.getPurchaseStatus()));
            rule.setLimitAmount(resolveLimit(item.getPurchaseStatus(), item.getDailyLimitAmount()));
            rules.add(rule);
        }

        stockFundPurchaseLimitService.saveCurrentRules(
                FundPurchaseLimitConstant.SOURCE_EM_FUND, FundPurchaseLimitConstant.SOURCE_EM_FUND_NAME, rules
        );

        if (stockSync == null) {
            stockSync = new StockSync();
            stockSync.setName(StockSyncConstant.STOCK_EM_FUND_PURCHASE_LIMIT_LATEST);
        }
        stockSync.setValue(String.valueOf(syncTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        stockSyncRepository.save(stockSync);
        log.info("同步全市场基金申购状态完成，targetFundCount={}, ruleCount={}", purchases.size(), rules.size());
    }

    /**
     * 将天天基金的中文申购状态映射为标准枚举：OPEN / LIMITED / SUSPENDED。
     */
    private String resolveStatus(String purchaseStatus) {
        if (StringUtils.isBlank(purchaseStatus)) {
            return FundPurchaseLimitConstant.STATUS_OPEN;
        }
        if (containsAny(purchaseStatus, "暂停", "不可", "封闭", "停止", "关闭")) {
            return FundPurchaseLimitConstant.STATUS_SUSPENDED;
        }
        if (containsAny(purchaseStatus, "限", "大额", "分批")) {
            return FundPurchaseLimitConstant.STATUS_LIMITED;
        }
        return FundPurchaseLimitConstant.STATUS_OPEN;
    }

    private BigDecimal resolveLimit(String purchaseStatus, BigDecimal dailyLimitAmount) {
        if (StringUtils.isBlank(purchaseStatus)
                || containsAny(purchaseStatus, "暂停", "不可", "封闭", "停止", "关闭")) {
            return null;
        }
        if (dailyLimitAmount != null && dailyLimitAmount.signum() > 0) {
            return dailyLimitAmount;
        }
        return null;
    }

    private boolean containsAny(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) {
                return true;
            }
        }
        return false;
    }
}