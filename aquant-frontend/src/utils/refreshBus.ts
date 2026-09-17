import { ref } from 'vue';

/**
 * 全局手动刷新总线。
 *
 * 顶部导航 Logo 区的「刷新」按钮调用 triggerRefresh()；
 * 页面在 onMounted 时用 registerRefreshHandler 注册自己的加载函数，
 * onUnmounted 时注销——因此任意时刻只有当前挂载的页面会响应刷新。
 */

export type RefreshHandler = () => void | Promise<unknown>;

const handlers = new Set<RefreshHandler>();

/** 刷新进行中：供刷新按钮做 loading 态 */
export const refreshing = ref(false);

/** 注册刷新回调，返回注销函数 */
export const registerRefreshHandler = (handler: RefreshHandler): (() => void) => {
  handlers.add(handler);
  return () => {
    handlers.delete(handler);
  };
};

/**
 * 触发一次全局刷新，等待所有注册的页面加载函数完成。
 * @returns 实际响应的页面数量（0 表示当前页面暂不支持刷新）
 */
export const triggerRefresh = async (): Promise<number> => {
  const targets = Array.from(handlers);
  if (targets.length === 0) {
    return 0;
  }
  refreshing.value = true;
  try {
    await Promise.allSettled(targets.map((handler) => handler()));
  } finally {
    refreshing.value = false;
  }
  return targets.length;
};
