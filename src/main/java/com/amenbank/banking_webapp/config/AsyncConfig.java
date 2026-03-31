package com.amenbank.banking_webapp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * CQ-6 fix: Custom async thread pool to replace Spring's default
 * SimpleAsyncTaskExecutor (which creates unbounded threads).
 */
@Configuration
@Slf4j
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("amenbank-async-");
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("Async task rejected — queue is full. Consider increasing queue capacity."));
        executor.initialize();
        log.info("AsyncConfig: Thread pool configured — core=2, max=5, queue=100");
        return executor;
    }
}

