package com.fjf.teproject.controller;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.service.SeckillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 分布式秒杀的 HTTP 接口。
 *
 * <p>接口契约见 docs/api/seckill.md。所有响应的 code 字段都是字符串，
 * 成功与失败形状一致，避免调用方需要判断类型才能解析。</p>
 */
@RestController
@RequestMapping("/api/seckill/activities")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /**
     * 执行一次抢购。
     *
     * <p>幂等：同一 (seckillId, userId) 重复请求不会二次扣减，稳定返回
     * ALREADY_PURCHASED（409）。</p>
     */
    @PostMapping("/{seckillId}/purchase")
    public ResponseEntity<Map<String, Object>> purchase(@PathVariable long seckillId,
                                                        @RequestBody(required = false) PurchaseRequest request) {
        String userId = request == null ? null : request.getUserId();
        if (userId == null || userId.trim().isEmpty()) {
            return buildError(SeckillErrorCode.INVALID_REQUEST);
        }

        DeductionOutcome outcome = seckillService.purchase(seckillId, userId.trim());
        if (!outcome.isSuccess()) {
            return buildError(outcome.getErrorCode());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("code", SeckillErrorCode.SUCCESS.getCode());
        body.put("message", SeckillErrorCode.SUCCESS.getMessage());
        body.put("remainingStock", outcome.getRemainingStock());
        return ResponseEntity.ok(body);
    }

    /** 查询实时库存快照（读 Redis）。其他并发请求可能在响应返回前继续扣减。 */
    @GetMapping("/{seckillId}/stock")
    public ResponseEntity<Map<String, Object>> getStock(@PathVariable long seckillId) {
        int remaining = seckillService.getRemainingStock(seckillId);

        Map<String, Object> body = new HashMap<>();
        body.put("code", SeckillErrorCode.SUCCESS.getCode());
        body.put("message", SeckillErrorCode.SUCCESS.getMessage());
        body.put("remainingStock", remaining);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> buildError(SeckillErrorCode errorCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", errorCode.getCode());
        body.put("message", errorCode.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    /** 抢购请求体。 */
    public static class PurchaseRequest {

        private String userId;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }
    }
}
