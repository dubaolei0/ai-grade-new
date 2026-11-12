package com.yuanxuan.aigrade;

import com.yuanxuan.aigrade.config.DashScopeProperties;
import com.yuanxuan.aigrade.config.DoubaoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({DashScopeProperties.class, DoubaoProperties.class})
public class AiGradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiGradeApplication.class, args);
    }
}
