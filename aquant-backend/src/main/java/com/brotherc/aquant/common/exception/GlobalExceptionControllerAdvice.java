package com.brotherc.aquant.common.exception;

import com.brotherc.aquant.common.model.dto.ResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.EOFException;
import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionControllerAdvice {

    @ExceptionHandler({BusinessException.class})
    public ResponseDTO<Void> handleBusinessException(BusinessException e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            log.error(e.getMessage(), cause);
        } else {
            log.error("BusinessException: ", e);
        }

        return ResponseDTO.fail(e.getCode(), e.getMsg(), null);
    }

    @ExceptionHandler({BindException.class})
    public ResponseDTO<Void> handleBindException(BindException e) {
        log.error("BindException: ", e);

        StringBuilder errorMsg = new StringBuilder();
        for (FieldError error : e.getFieldErrors()) {
            errorMsg.append(error.getField()).append(":").append(error.getDefaultMessage()).append(";");
        }
        String msg = errorMsg.substring(0, errorMsg.length());

        return ResponseDTO.fail(ExceptionEnum.SYS_CHECK_ERROR.getCode(), msg, null);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class})
    public ResponseDTO<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("MethodArgumentNotValidException: ", e);

        StringBuilder errorMsg = new StringBuilder();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errorMsg.append(error.getField()).append(":").append(error.getDefaultMessage()).append(";");
        }
        String msg = errorMsg.substring(0, errorMsg.length());

        return ResponseDTO.fail(ExceptionEnum.SYS_CHECK_ERROR.getCode(), msg, null);
    }

    /**
     * 客户端提前断开连接：浏览器取消请求、页面跳转、自动化工具关掉页面等。
     * <p>
     * 这类异常不是系统故障（响应根本发不出去），用 ERROR 记录会把真正的错误淹没在噪音里，
     * 实测一次页面刷新就能刷出几十条。这里只记 debug，且不打堆栈。
     */
    @ExceptionHandler({ClientAbortException.class, IOException.class, EOFException.class})
    public ResponseDTO<Void> handleClientAbortException(Exception e) {
        log.debug("客户端取消请求或连接已断开: {}", e.toString());
        return ResponseDTO.fail(ExceptionEnum.SYS_ERROR.getCode(), ExceptionEnum.SYS_ERROR.getMsg(), null);
    }

    @ExceptionHandler({Exception.class})
    public ResponseDTO<Void> handleException(Exception e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            log.error(e.getMessage(), cause);
        } else {
            log.error("", e);
        }

        return ResponseDTO.fail(ExceptionEnum.SYS_ERROR.getCode(), ExceptionEnum.SYS_ERROR.getMsg(), null);
    }

}
