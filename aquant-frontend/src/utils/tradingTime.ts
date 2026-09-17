/**
 * A 股交易时段判定。
 *
 * 交易时段：工作日 09:30-11:30、13:00-15:00。
 * 注意：这里只按「星期」排除周末，未接入 stock_trade_calendar 的节假日数据；
 * 法定节假日若恰好是工作日，仍会被算作交易时段——只是多刷几次无变化的请求，影响可忽略。
 */

/** 每日交易时段（分钟数），可按需增减 */
const SESSION_MINUTES: ReadonlyArray<readonly [number, number]> = [
  [9 * 60 + 30, 11 * 60 + 30],
  [13 * 60, 15 * 60]
];

export const isTradingTime = (now: Date = new Date()): boolean => {
  const day = now.getDay();
  if (day === 0 || day === 6) {
    return false;
  }
  const minutes = now.getHours() * 60 + now.getMinutes();
  return SESSION_MINUTES.some(([start, end]) => minutes >= start && minutes <= end);
};
