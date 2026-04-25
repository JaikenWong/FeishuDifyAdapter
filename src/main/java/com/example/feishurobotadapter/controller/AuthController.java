package com.example.feishurobotadapter.controller;

import com.example.feishurobotadapter.dto.LoginRequest;
import com.example.feishurobotadapter.dto.UserResponse;
import com.example.feishurobotadapter.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public UserResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        httpRequest.changeSessionId();
        return authService.login(request, httpRequest.getSession());
    }

    @PostMapping("/logout")
    public void logout(HttpSession session) {
        authService.logout(session);
    }

    @GetMapping("/me")
    public UserResponse me(HttpSession session) {
        return authService.current(session);
    }
}
