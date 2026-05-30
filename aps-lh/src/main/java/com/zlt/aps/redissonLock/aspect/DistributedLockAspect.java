package com.zlt.aps.redissonLock.aspect;

import com.zlt.aps.redissonLock.annotation.DistributedLock;
import com.zlt.aps.redissonLock.annotation.DistributedLockException;
import com.zlt.aps.utils.SpELi18nUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 分布式锁注解切面（支持国际化+参数化占位符）
 *
 * @author wengpc
 */
@Aspect
@Component
@Slf4j
public class DistributedLockAspect {

    @Autowired
    private RedissonClient redissonClient;

    @Around("@annotation(com.zlt.aps.redissonLock.annotation.DistributedLock)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取注解和方法信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);

        // 解析SpEL生成锁key
        String lockKey = SpELi18nUtil.parseSpEL(distributedLock.key(), joinPoint.getArgs(), method);
        RLock lock = redissonClient.getLock(lockKey);

        boolean isLocked = false;
        try {
            // 尝试获取锁
            isLocked = lock.tryLock(
                    distributedLock.waitTime(),
                    distributedLock.leaseTime(),
                    distributedLock.timeUnit()
            );
            if (!isLocked) {
                // 解析国际化提示信息（含参数替换）
                String failMessage = SpELi18nUtil.getI18nMessage(distributedLock.failMsg(), distributedLock.args(), joinPoint, method);
                throw new DistributedLockException(failMessage);
            }
            // 执行目标方法
            return joinPoint.proceed();
        } finally {
            // 释放锁
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
