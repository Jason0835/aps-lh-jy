package com.zlt.aps.redissonLock.annotation;

/**
 * 分布式锁获取失败异常
 *
 * @author wengpc
 */
public class DistributedLockException extends RuntimeException {

    public DistributedLockException(String message) {
        super(message);
    }
}
