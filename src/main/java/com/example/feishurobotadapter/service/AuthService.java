package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.LoginRequest;
import com.example.feishurobotadapter.dto.UserResponse;
import jakarta.servlet.http.HttpSession;

public interface AuthService {

    UserResponse login(LoginRequest request, HttpSession session);

    void logout(HttpSession session);

    UserResponse current(HttpSession session);
}
