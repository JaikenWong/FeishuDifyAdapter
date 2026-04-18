package com.example.feishurobotadapter.service;

public interface FeishuLongConnectionManager {

    void enable(Long configId);

    void disable(Long configId);

    boolean isEnabled(Long configId);
}
