package com.example.feishurobotadapter.repository;

import com.example.feishurobotadapter.entity.BotConfig;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BotConfigRepository extends JpaRepository<BotConfig, Long> {

    List<BotConfig> findAllByOrderByCreatedAtDesc();

    List<BotConfig> findAllByOrderByRobotNameAsc();

    List<BotConfig> findByLongConnectionEnabledTrueOrderByCreatedAtDesc();

    @Query("""
            SELECT b FROM BotConfig b
            WHERE LOWER(b.robotName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(b.appId) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(b.difyUrl) LIKE LOWER(CONCAT('%', :keyword, '%'))
            ORDER BY b.createdAt DESC
            """)
    List<BotConfig> searchByKeywordOrderByCreatedAtDesc(@Param("keyword") String keyword);

    @Query("""
            SELECT b FROM BotConfig b
            WHERE LOWER(b.robotName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(b.appId) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(b.difyUrl) LIKE LOWER(CONCAT('%', :keyword, '%'))
            ORDER BY b.robotName ASC
            """)
    List<BotConfig> searchByKeywordOrderByRobotNameAsc(@Param("keyword") String keyword);
}
