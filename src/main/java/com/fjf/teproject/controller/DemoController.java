package com.fjf.teproject.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Demo API 接口
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    /**
     * 基础接口：GET /api/demo/hello
     */
    @GetMapping("/hello")
    public Map<String, Object> hello() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", "Hello, TeProject!");
        result.put("timestamp", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 带查询参数：GET /api/demo/greet?name=Neal
     */
    @GetMapping("/greet")
    public Map<String, Object> greet(@RequestParam(defaultValue = "World") String name) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", "Hello, " + name + "!");
        return result;
    }

    /**
     * 带路径参数：GET /api/demo/user/123
     */
    @GetMapping("/user/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        Map<String, Object> user = new HashMap<>();
        user.put("id", id);
        user.put("name", "用户" + id);
        user.put("createdAt", LocalDateTime.now().toString());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", user);
        return result;
    }
}
