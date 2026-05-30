package com.zlt.aps.lh.config;

import com.zlt.aps.utils.SpELi18nUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Redisson 与 SpEL 国际化工具初始化配置
 *
 * @author wengpc
 */
@Configuration
public class LhLockConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.database:0}")
    private int redisDatabase;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Resource
    private ApplicationContext applicationContext;

    /**
     * 创建 RedissonClient 实例，复用已有 Redis 连接配置
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                .setPassword(redisPassword.isEmpty() ? null : redisPassword);
        return Redisson.create(config);
    }

    /**
     * 注入 Spring MessageSource 到 SpEL 工具类
     */
    @PostConstruct
    public void initSpELMessageSource() {
        SpELi18nUtil.setMessageSource(applicationContext);
    }
}
