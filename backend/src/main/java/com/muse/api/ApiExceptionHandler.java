package com.muse.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiModels.Error> api(ApiException error) {
        return error(error.status(), error.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiModels.Error> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5MB");
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            HttpMediaTypeNotSupportedException.class, MultipartException.class})
    ResponseEntity<ApiModels.Error> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "请求参数无效，请检查图片、模板和模拟设置（每步延迟 0–30000 毫秒）");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiModels.Error> notFound() {
        return error(HttpStatus.NOT_FOUND, "请求的资源不存在");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiModels.Error> wrongMethod() {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "不支持此请求方法");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiModels.Error> databaseUnavailable(DataAccessException exception) {
        log.error("Database operation failed", exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "数据服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiModels.Error> unexpected(Exception exception) {
        log.error("Request failed", exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试");
    }

    private ResponseEntity<ApiModels.Error> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiModels.Error(message));
    }
}
