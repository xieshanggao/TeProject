package com.fjf.teproject.controller;

import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.domain.SeckillException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

/**
 * 把业务异常转换为 HTTP 响应，是 spec §8.2 失败矩阵的落地处。
 *
 * <p>HTTP 状态与响应体中的 code 都取自 {@link SeckillErrorCode}，
 * 保证接口契约只有一个事实来源。</p>
 *
 * <p><b>Redis 不可用必须快速失败为 503，绝不降级去读 DB。</b>一旦部分请求走
 * Redis、部分走 DB，两个存储各自扣减，I1 与 I3 立刻被破坏，必然超卖。
 * 建议路径上唯一的存储就是 Redis，因此 {@link DataAccessException}
 * （{@code RedisConnectionFailureException} 是它的子类）在这里等价于
 * 「无法判定库存」，按 {@code NOT_READY} 返回。</p>
 */
@RestControllerAdvice
public class SeckillExceptionHandler {

    @ExceptionHandler(SeckillException.class)
    public ResponseEntity<Map<String, Object>> handleSeckillException(SeckillException exception) {
        return buildResponse(exception.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return buildResponse(SeckillErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        return buildResponse(SeckillErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccessFailure(DataAccessException exception) {
        return buildResponse(SeckillErrorCode.NOT_READY);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(SeckillErrorCode errorCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", errorCode.getCode());
        body.put("message", errorCode.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
