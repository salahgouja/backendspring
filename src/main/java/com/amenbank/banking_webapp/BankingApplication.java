package com.amenbank.banking_webapp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableCaching
@EnableTransactionManagement
@Slf4j
public class BankingApplication {

    private static final String APP_NAME = "AmenBank-Core-Banking";

    public static void main(String[] args) {
        log.info("=== Starting {} Application ===", APP_NAME);
        
        long startTime = System.currentTimeMillis();
        
        SpringApplication.run(BankingApplication.class, args);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("=== {} Started in {} ms ===", APP_NAME, duration);
    }

    // ============================================================
    // Bean Life Cycle - @PostConstruct
    // Executed after bean is constructed and dependencies injected
    // ============================================================
    @PostConstruct
    public void onStartup() {
        log.info("=== {}: PostConstruct - Application Startup ===", APP_NAME);
        log.info("{}: Environment: {}", APP_NAME, System.getProperty("spring.profiles.active", "default"));
        log.info("{}: Java Version: {}", APP_NAME, System.getProperty("java.version"));
        log.info("{}: Available Processors: {}", APP_NAME, Runtime.getRuntime().availableProcessors());
        log.info("{}: Max Memory: {} MB", APP_NAME, Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    // ============================================================
    // Bean Life Cycle - @PreDestroy
    // Executed before bean is destroyed (application shutdown)
    // ============================================================
    @PreDestroy
    public void onShutdown() {
        log.info("=== {}: PreDestroy - Application Shutdown ===", APP_NAME);
        
        // Clean up resources
        log.info("{}: Closing connections and releasing resources", APP_NAME);
        
        // Force garbage collection hint
        System.gc();
        
        log.info("{}: PreDestroy completed - Shutdown gracefully", APP_NAME);
    }
}
