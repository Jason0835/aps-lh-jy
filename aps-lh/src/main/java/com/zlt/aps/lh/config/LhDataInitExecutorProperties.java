package com.zlt.aps.lh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 硫化基础数据初始化线程池配置。
 *
 * @author APS
 */
@Data
@Component
@ConfigurationProperties(prefix = "aps.lh.data-init.executor")
public class LhDataInitExecutorProperties {

    /** 默认核心线程数 */
    private static final int DEFAULT_CORE_POOL_SIZE = 4;

    /** 默认最大线程数 */
    private static final int DEFAULT_MAX_POOL_SIZE = 8;

    /** 默认队列容量 */
    private static final int DEFAULT_QUEUE_CAPACITY = 32;

    /** 默认空闲线程存活秒数 */
    private static final int DEFAULT_KEEP_ALIVE_SECONDS = 60;

    /** 默认线程名前缀 */
    private static final String DEFAULT_THREAD_NAME_PREFIX = "lh-data-init-";

    /** 核心线程数 */
    private int corePoolSize = DEFAULT_CORE_POOL_SIZE;

    /** 最大线程数 */
    private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;

    /** 队列容量 */
    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

    /** 空闲线程存活秒数 */
    private int keepAliveSeconds = DEFAULT_KEEP_ALIVE_SECONDS;

    /** 线程名前缀 */
    private String threadNamePrefix = DEFAULT_THREAD_NAME_PREFIX;
}
