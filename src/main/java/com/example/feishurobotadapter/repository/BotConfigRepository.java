package com.example.feishurobotadapter.repository;

import com.example.feishurobotadapter.entity.BotConfig;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotConfigRepository extends JpaRepository<BotConfig, Long> {

    List<BotConfig> findAllByOrderByCreatedAtDesc();

    List<BotConfig> findByLongConnectionEnabledTrueOrderByCreatedAtDesc();
}
