package com.example.feishurobotadapter.util;

import com.example.feishurobotadapter.config.AppProperties;
import com.example.feishurobotadapter.entity.User;
import com.example.feishurobotadapter.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void initAdmin() {
        if (userRepository.findByUsername(appProperties.defaultAdminUsername()).isPresent()) {
            return;
        }
        User user = new User();
        user.setUsername(appProperties.defaultAdminUsername());
        user.setPasswordHash(passwordEncoder.encode(appProperties.defaultAdminPassword()));
        userRepository.save(user);
    }
}
