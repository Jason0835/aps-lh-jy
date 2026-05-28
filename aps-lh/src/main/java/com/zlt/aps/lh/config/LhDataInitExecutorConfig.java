package com.zlt.aps.lh.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 硫化基础数据初始化线程池配置。
 *
 * @author APS
 */
@Configuration
public class LhDataInitExecutorConfig {

    /** 最小线程数 */
    private static final int MIN_POOL_SIZE = 1;

    /** 默认线程名前缀 */
    private static final String DEFAULT_THREAD_NAME_PREFIX = "lh-data-init-";

    /**
     * 创建硫化基础数据初始化线程池。
     *
     * @param properties 线程池配置
     * @return Spring 托管线程池
     */
    @Bean(name = "lhDataInitExecutor")
    public ThreadPoolTaskExecutor lhDataInitExecutor(LhDataInitExecutorProperties properties) {
        int corePoolSize = Math.max(MIN_POOL_SIZE, properties.getCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, properties.getMaxPoolSize());
        int queueCapacity = Math.max(MIN_POOL_SIZE, properties.getQueueCapacity());
        int keepAliveSeconds = Math.max(0, properties.getKeepAliveSeconds());
        String threadNamePrefix = StringUtils.isNotEmpty(properties.getThreadNamePrefix())
                ? properties.getThreadNamePrefix()
                : DEFAULT_THREAD_NAME_PREFIX;

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        // 队列满时由提交线程执行，避免丢弃初始化任务，同时自然限制瞬时并发。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
