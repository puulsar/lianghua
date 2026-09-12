package com.brotherc.aquant.strategy.model.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 量化策略参数配置（超管可配置，落库到 sys_config，value 存 JSON）。
 * <p>
 * 网格比例 rate 以百分比存（3 表示 3%），生成快照时再除以 100。
 */
@Data
public class StrategyConfig {

    private DualMa dualMa;
    private Momentum momentum;
    private Macd macd;
    private Grid grid;

    @Data
    public static class DualMa {
        private List<Integer> maShort;
        private List<Integer> maLong;
        private List<Integer> years;

        public static DualMa of(List<Integer> maShort, List<Integer> maLong, List<Integer> years) {
            DualMa c = new DualMa();
            c.setMaShort(new ArrayList<>(maShort));
            c.setMaLong(new ArrayList<>(maLong));
            c.setYears(new ArrayList<>(years));
            return c;
        }
    }

    @Data
    public static class Momentum {
        private List<Integer> lookback;
        private List<Integer> years;

        public static Momentum of(List<Integer> lookback, List<Integer> years) {
            Momentum c = new Momentum();
            c.setLookback(new ArrayList<>(lookback));
            c.setYears(new ArrayList<>(years));
            return c;
        }
    }

    @Data
    public static class Macd {
        private int fast;
        private int slow;
        private int signal;
        private List<Integer> years;

        public static Macd of(int fast, int slow, int signal, List<Integer> years) {
            Macd c = new Macd();
            c.setFast(fast);
            c.setSlow(slow);
            c.setSignal(signal);
            c.setYears(new ArrayList<>(years));
            return c;
        }
    }

    @Data
    public static class Grid {
        /** 百分比，如 3 表示 3% */
        private int rate;
        private int count;
        private List<Integer> years;

        public static Grid of(int rate, int count, List<Integer> years) {
            Grid c = new Grid();
            c.setRate(rate);
            c.setCount(count);
            c.setYears(new ArrayList<>(years));
            return c;
        }
    }
}