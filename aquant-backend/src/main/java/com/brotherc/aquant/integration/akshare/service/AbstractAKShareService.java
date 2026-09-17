package com.brotherc.aquant.integration.akshare.service;

import com.brotherc.aquant.common.exception.BusinessException;
import com.brotherc.aquant.common.exception.ExceptionEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.List;

/**
 * AKShare 请求基础抽象类
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractAKShareService {

    protected static final String SYMBOL = "symbol";

    @Value("${akshare-address}")
    protected String akshareAddress;

    protected final ObjectMapper objectMapper;
    protected final OkHttpClient okHttpClient;

    /**
     * 从 Request 的 URL 中自动解析并截取 API 名称（取 Path 的最后一个 segment，如 stock_zh_a_spot）
     */
    protected String extractApiName(Request request) {
        List<String> segments = request.url().pathSegments();
        if (!segments.isEmpty()) {
            return segments.get(segments.size() - 1);
        }
        return request.url().encodedPath();
    }

    /**
     * 通用 HTTP 请求处理：执行 Request 并自动转为目标 Java 对象。
     * <p>
     * 对上游 aktools 的 5xx / 网络异常做有限重试——aktools 的实时行情接口（stock_zh_a_spot）
     * 实测经常间歇性返回 500 或 HTML 错误页（JSON 解析失败），重试通常能命中成功的一次。
     * 4xx 客户端错误不重试（重试无意义），直接抛出。
     */
    private static final int AKSHARE_MAX_ATTEMPTS = 3;
    private static final long AKSHARE_RETRY_BACKOFF_MILLIS = 2_000L;

    protected <T> T executeRequest(Request request, TypeReference<T> typeReference) {
        String apiName = extractApiName(request);
        BusinessException lastError = null;
        for (int attempt = 1; attempt <= AKSHARE_MAX_ATTEMPTS; attempt++) {
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return objectMapper.readValue(response.body().string(), typeReference);
                }
                String errorBody = response.body() == null ? null : response.body().string();
                log.warn("{} 失败响应(第{}/{}次): status={}, body={}",
                        apiName, attempt, AKSHARE_MAX_ATTEMPTS, response.code(), errorBody);
                // 4xx 客户端错误不重试
                if (response.code() > 0 && response.code() < 500) {
                    throw new BusinessException(ExceptionEnum.API_REQUEST_ERROR);
                }
                lastError = new BusinessException(ExceptionEnum.API_REQUEST_ERROR);
            } catch (BusinessException e) {
                // 4xx 已抛出，不再重试
                throw e;
            } catch (IOException e) {
                // 网络异常 / 响应体非 JSON（如上游返回 HTML 错误页）均按可重试异常处理
                log.warn("{} 请求失败(第{}/{}次)", apiName, attempt, AKSHARE_MAX_ATTEMPTS, e);
                lastError = new BusinessException(ExceptionEnum.API_REQUEST_ERROR);
            } catch (Exception e) {
                log.warn("{} 处理响应失败(第{}/{}次)", apiName, attempt, AKSHARE_MAX_ATTEMPTS, e);
                lastError = new BusinessException(ExceptionEnum.API_REQUEST_ERROR);
            }
            if (attempt < AKSHARE_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(AKSHARE_RETRY_BACKOFF_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastError != null ? lastError : new BusinessException(ExceptionEnum.API_REQUEST_ERROR);
    }

    /**
     * 通用 GET 请求重载（接收 String URL）
     */
    protected <T> T executeGet(String url, TypeReference<T> typeReference) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return executeRequest(request, typeReference);
    }

    /**
     * 通用 GET 请求重载（接收 HttpUrl 构造器）
     */
    protected <T> T executeGet(HttpUrl httpUrl, TypeReference<T> typeReference) {
        Request request = new Request.Builder()
                .url(httpUrl)
                .get()
                .build();
        return executeRequest(request, typeReference);
    }

}
