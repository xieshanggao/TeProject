package com.fjf.teproject.controller;

import com.fjf.teproject.service.SeckillInventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillInventoryService seckillInventoryService;

    public SeckillController(SeckillInventoryService seckillInventoryService) {
        this.seckillInventoryService = seckillInventoryService;
    }

    /**
     * 执行一次固定购买一件的内存秒杀请求。
     *
     * <p>售罄时使用 {@code 409 Conflict}，让调用方能够区分请求正常处理但库存不足的情况。</p>
     */
    @PostMapping("/purchase")
    public ResponseEntity<Map<String, Object>> purchase() {
        Map<String, Object> result = new HashMap<>();
        int remainingStock = seckillInventoryService.purchase();

        if (remainingStock >= 0) {
            result.put("code", 0);
            result.put("message", "抢购成功");
            result.put("remainingStock", remainingStock);
            return ResponseEntity.ok(result);
        }

        result.put("code", "STOCK_SOLD_OUT");
        result.put("message", "库存已售罄");
        result.put("remainingStock", 0);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    /**
     * 查询库存快照，仅用于演示和接口验证。
     */
    @GetMapping("/stock")
    public Map<String, Object> getStock() {
        Map<String, Object> result = new HashMap<>();
        result.put("remainingStock", seckillInventoryService.getRemainingStock());
        return result;
    }
}
