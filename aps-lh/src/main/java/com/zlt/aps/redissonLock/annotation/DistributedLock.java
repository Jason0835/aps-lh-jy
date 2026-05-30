package com.zlt.aps.redissonLock.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解（支持国际化+参数化占位符）
 *
 * @author wengpc
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {
    /**
     * 分布式锁的key（支持SpEL表达式，如："order:#orderId"）
     */
    String key();

    /**
     * 获取锁的等待时间（默认5秒）
     */
    long waitTime() default 5;

    /**
     * 锁的自动过期时间（当值=-1时，启动看门狗机制，锁超时:默认30s，会自动续期）
     */
    long leaseTime() default 30;

    /**
     * 时间单位（默认秒）
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 获取锁失败时的国际化KEY
     * 示例：ui.data.alert.distributed.lock.fail
     */
    String failMsg() default "ui.data.alert.distributed.lock.fail";

    /**
     * 填充国际化文本占位符的参数（支持SpEL表达式，数组顺序对应占位符{0}、{1}...）
     * 示例：{"#yearMonth", "#orderId"}
     */
    String[] args() default {};
}
