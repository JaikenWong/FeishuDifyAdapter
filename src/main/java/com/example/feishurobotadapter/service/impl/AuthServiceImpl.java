package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.config.AppProperties;
import com.example.feishurobotadapter.dto.LoginRequest;
import com.example.feishurobotadapter.dto.UserResponse;
import com.example.feishurobotadapter.entity.User;
import com.example.feishurobotadapter.repository.UserRepository;
import com.example.feishurobotadapter.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    @Override
    public UserResponse login(LoginRequest request, HttpSession session) {
        User user = userRepository.findByUsername(request.username())
                .filter(item -> passwordEncoder.matches(request.password(), item.getPasswordHash()))
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        UserResponse response = new UserResponse(user.getId(), user.getUsername());
        session.setAttribute(appProperties.sessionKey(), response);
        return response;
    }

    @Override
    public void logout(HttpSession session) {
        session.invalidate();
    }

    @Override
    public UserResponse current(HttpSession session) {
        Object current = session.getAttribute(appProperties.sessionKey());
        if (current instanceof UserResponse response) {
            return response;
        }
        throw new IllegalArgumentException("未登录");
    }
}
