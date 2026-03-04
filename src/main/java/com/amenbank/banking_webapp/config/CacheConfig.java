package com.amenbank.banking_webapp.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableCaching
@Slf4j
public class CacheConfig {

    private static final String BEAN_NAME = "CacheConfig";
    private ScheduledExecutorService cacheMonitor;

    @PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - Cache Configuration initialized ===", BEAN_NAME);
        cacheMonitor = Executors.newScheduledThreadPool(1);
        log.info("{}: Cache monitoring scheduled", BEAN_NAME);
    }

    @PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Cleaning up Cache resources ===", BEAN_NAME);
        if (cacheMonitor != null) {
            cacheMonitor.shutdown();
        }
        log.info("{}: PreDestroy completed", BEAN_NAME);
    }

    @Bean
    public CacheManager cacheManager() {
        log.info("{}: Creating ConcurrentMapCacheManager", BEAN_NAME);
        
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        cacheManager.setCacheNames(java.util.List.of(
                "userCache", "tokenCache", "accountCache", 
                "transactionCache", "notificationCache"));
        cacheManager.setAllowNullValues(true);
        
        return cacheManager;
    }
}
