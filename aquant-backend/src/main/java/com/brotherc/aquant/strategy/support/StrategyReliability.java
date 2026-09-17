package com.brotherc.aquant.strategy.support;

import com.brotherc.aquant.strategy.model.vo.StockTradeBacktestVO;
import lombok.Getter;
import org.apache.commons.math3.stat.inference.TTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 策略回测的统计可靠性评估。
 *
 * <p>评估口径（四个策略共用）：
 * <ol>
 *     <li>样本必须是<strong>配对交易</strong>的收益率：一次买入对应一次卖出，
 *         收益率 =（卖出价 − 该笔买入价）/ 该笔买入价。
 *         早期实现用「相对滚动平均成本的浮盈」做样本，同一次建仓会产生多个高度相关的样本，
 *         不满足 t 检验的独立同分布前提，这里不再使用。</li>
 *     <li>样本量低于 {@link #MIN_SAMPLE_SIZE} 时评为「样本不足」，不进行显著性判断。
 *         t 统计量随样本量放大，样本太少时评级实际由样本量而非策略质量决定。</li>
 *     <li>全市场同时检验上千只股票，必须做多重检验校正，否则 α=0.05 下
 *         会有大量纯靠运气的「显著」结果。这里用 Benjamini-Hochberg 方法控制 FDR，
 *         由 {@link #applyFdr(List)} 在拿到完整结果集后统一执行。</li>
 * </ol>
 */
public final class StrategyReliability {

    /**
     * 给出可靠度评级所需的最小配对交易样本量。
     */
    public static final int MIN_SAMPLE_SIZE = 30;

    public static final String INSUFFICIENT = "样本不足";
    public static final String HIGH = "高";
    public static final String MIDDLE = "中";
    public static final String LOW = "低";
    public static final String LOW_ZERO_VARIANCE = "低(方差0)";

    private static final double FDR_STRICT = 0.05D;
    private static final double FDR_LOOSE = 0.10D;
    private static final int RATE_SCALE = 4;

    private static final TTest T_TEST = new TTest();

    private StrategyReliability() {
    }

    /**
     * 单只股票的评估结果。
     */
    @Getter
    public static final class Result {
        private final int sampleCount;
        private final BigDecimal winRate;
        private final Double tValue;
        private final Double pValue;
        private final String reliability;

        Result(int sampleCount, BigDecimal winRate, Double tValue, Double pValue, String reliability) {
            this.sampleCount = sampleCount;
            this.winRate = winRate;
            this.tValue = tValue;
            this.pValue = pValue;
            this.reliability = reliability;
        }
    }

    /**
     * 基于配对交易收益率计算胜率、t 统计量与单侧 p 值（原假设：平均单笔收益 = 0）。
     */
    public static Result evaluate(List<Double> pairedReturns) {
        int sampleCount = pairedReturns == null ? 0 : pairedReturns.size();
        if (sampleCount == 0) {
            return new Result(0, BigDecimal.ZERO, null, null, INSUFFICIENT);
        }

        int winCount = 0;
        double sum = 0D;
        for (double value : pairedReturns) {
            sum += value;
            if (value > 0D) {
                winCount++;
            }
        }
        BigDecimal winRate = BigDecimal.valueOf(winCount)
                .divide(BigDecimal.valueOf(sampleCount), RATE_SCALE, RoundingMode.HALF_UP);

        if (sampleCount < 2) {
            return new Result(sampleCount, winRate, null, null, INSUFFICIENT);
        }

        double[] samples = pairedReturns.stream().mapToDouble(Double::doubleValue).toArray();
        double sampleMean = sum / sampleCount;
        double tValue;
        double twoSidedPValue;
        try {
            tValue = T_TEST.t(0D, samples);
            twoSidedPValue = T_TEST.tTest(0D, samples);
        } catch (Exception ignored) {
            tValue = Double.NaN;
            twoSidedPValue = Double.NaN;
        }
        if (!Double.isFinite(tValue) || !Double.isFinite(twoSidedPValue)) {
            String reliability = sampleMean > 0D ? LOW_ZERO_VARIANCE : LOW;
            return new Result(sampleCount, winRate, null, null, reliability);
        }

        double oneSidedPValue = tValue > 0D ? twoSidedPValue / 2D : 1D - twoSidedPValue / 2D;
        String reliability = sampleCount < MIN_SAMPLE_SIZE
                ? INSUFFICIENT
                : rate(sampleMean, oneSidedPValue);
        return new Result(sampleCount, winRate, tValue, oneSidedPValue, reliability);
    }

    /**
     * Benjamini-Hochberg 多重检验校正，并据此重写可靠度评级。
     *
     * <p>必须在拿到<strong>完整检验集合</strong>后调用（全市场或单个市场全部结果），
     * 只对样本量达标的条目做校正；p 值为空或样本不足的条目保持原评级。
     * 校正后 write 回的是 q 值判定结果，VO 上的 pValue 仍保留原始单侧 p 值。
     */
    public static void applyFdr(List<StockTradeBacktestVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<StockTradeBacktestVO> tested = new ArrayList<>(rows.size());
        for (StockTradeBacktestVO row : rows) {
            if (row == null || row.getPValue() == null || !Double.isFinite(row.getPValue())) {
                continue;
            }
            if (row.getTradeCount() == null || row.getTradeCount() < MIN_SAMPLE_SIZE) {
                continue;
            }
            tested.add(row);
        }
        if (tested.isEmpty()) {
            return;
        }

        tested.sort(Comparator.comparingDouble(StockTradeBacktestVO::getPValue));
        int total = tested.size();
        double running = Double.POSITIVE_INFINITY;
        for (int i = total - 1; i >= 0; i--) {
            StockTradeBacktestVO row = tested.get(i);
            double candidate = row.getPValue() * total / (i + 1);
            if (candidate < running) {
                running = candidate;
            }
            if (running > 1D) {
                running = 1D;
            }
            Double tValue = row.getTValue();
            row.setReliability(rateBySign(tValue != null && tValue > 0D, running));
        }
    }

    private static String rate(double sampleMean, double pValue) {
        return rateBySign(sampleMean > 0D, pValue);
    }

    private static String rateBySign(boolean positiveMean, double pValue) {
        if (positiveMean && pValue < FDR_STRICT) {
            return HIGH;
        }
        if (positiveMean && pValue < FDR_LOOSE) {
            return MIDDLE;
        }
        return LOW;
    }
}
