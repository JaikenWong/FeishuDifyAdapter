package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.config.AppProperties;
import com.example.feishurobotadapter.dto.LoginRequest;
import com.example.feishurobotadapter.dto.UserResponse;
import com.example.feishurobotadapter.entity.User;
import com.example.feishurobotadapter.repository.UserRepository;
import com.example.feishurobotadapter.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AuthServiceImpl implements AuthService {

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_MILLIS = 5 * 60 * 1000;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final ConcurrentHashMap<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    @Override
    public UserResponse login(LoginRequest request, HttpSession session) {
        String username = request.username();
        checkLoginRateLimit(username);
        try {
            User user = userRepository.findByUsername(username)
                    .filter(item -> passwordEncoder.matches(request.password(), item.getPasswordHash()))
                    .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
            loginAttempts.remove(username);
            UserResponse response = new UserResponse(user.getId(), user.getUsername());
            session.setAttribute(appProperties.sessionKey(), response);
            return response;
        } catch (IllegalArgumentException ex) {
            recordFailedAttempt(username);
            throw ex;
        }
    }

    private void checkLoginRateLimit(String username) {
        LoginAttempt attempt = loginAttempts.get(username);
        if (attempt != null && attempt.isExpired()) {
            loginAttempts.remove(username, attempt);
            attempt = null;
        }
        if (attempt != null && attempt.isLockedOut()) {
            throw new IllegalArgumentException("登录尝试过多，请5分钟后再试");
        }
    }

    private void recordFailedAttempt(String username) {
        loginAttempts.compute(username, (k, existing) -> {
            if (existing == null || existing.isExpired()) {
                return new LoginAttempt(1);
            }
            existing.increment();
            return existing;
        });
    }

    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void evictExpiredAttempts() {
        loginAttempts.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private static class LoginAttempt {
        private final AtomicInteger count;
        private final long firstAttemptTime;

        LoginAttempt(int initialCount) {
            this.count = new AtomicInteger(initialCount);
            this.firstAttemptTime = System.currentTimeMillis();
        }

        void increment() {
            count.incrementAndGet();
        }

        boolean isLockedOut() {
            return !isExpired() && count.get() >= MAX_LOGIN_ATTEMPTS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - firstAttemptTime > LOCKOUT_MILLIS;
        }
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
