package com.brotherc.aquant.common.constant;

public final class FundPurchaseLimitConstant {

    private FundPurchaseLimitConstant() {
    }

    public static final String CHANNEL_DIRECT = "DIRECT";
    public static final String CHANNEL_ALL = "ALL_CHANNELS";
    public static final String CHANNEL_DIRECT_NAME = "官方直销";
    public static final String CHANNEL_ALL_NAME = "全部渠道";
    public static final String BUSINESS_PURCHASE = "PURCHASE";
    public static final String BUSINESS_RECURRING = "RECURRING_INVESTMENT";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_LIMITED = "LIMITED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    /** 全市场基金官方申购状态（来自天天基金 fund_purchase_em 列表） */
    public static final String SOURCE_EM_FUND = "EM_FUND";
    public static final String SOURCE_EM_FUND_NAME = "天天基金·全市场";

    public static final String SYNC_SUCCESS = "SUCCESS";
    public static final String SYNC_IGNORED = "IGNORED";
    public static final String SYNC_FAILED = "FAILED";
    public static final String SYNC_PENDING = "PENDING";
}
