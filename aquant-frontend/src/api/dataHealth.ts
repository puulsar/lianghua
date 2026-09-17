
import type { ResponseDTO } from './stock';
import api from '@/utils/request';

export type HealthStatus = 'PASS' | 'WARN' | 'FAIL';

export interface DataHealthCheckItemVO {
    itemKey: string;
    itemName: string;
    status: HealthStatus;
    expected: string;
    actual: string;
    message: string;
    sortOrder: number;
}

export interface DataHealthCheckVO {
    id: number;
    tradeDate: string;
    checkTime: string;
    status: HealthStatus;
    passCount: number;
    warnCount: number;
    failCount: number;
    durationMillis: number;
    triggerType: string;
    summary: string;
    items: DataHealthCheckItemVO[];
}

export const getLatestHealthCheck = () => {
    return api.get<ResponseDTO<DataHealthCheckVO>>('/dataHealth/latest');
};

export const getRecentHealthChecks = (limit = 10) => {
    return api.get<ResponseDTO<DataHealthCheckVO[]>>('/dataHealth/recent', { params: { limit } });
};

/** 手动触发一次体检（需要登录） */
export const triggerHealthCheck = () => {
    return api.post<ResponseDTO<DataHealthCheckVO>>('/admin/sync/health-check');
};

/** 手动触发收盘数据收口作业（需要登录，异步执行） */
export const triggerDailyCloseJob = () => {
    return api.post<ResponseDTO<string>>('/admin/sync/daily-close');
};
