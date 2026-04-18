package com.example.feishurobotadapter.controller;

import com.example.feishurobotadapter.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ApiResponse health() {
        return new ApiResponse(true, "ok");
    }
}
