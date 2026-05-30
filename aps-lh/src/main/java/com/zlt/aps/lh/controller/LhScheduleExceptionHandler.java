package com.zlt.aps.lh.controller;

import com.zlt.aps.lh.api.domain.dto.LhScheduleResponseDTO;
import com.zlt.aps.redissonLock.annotation.DistributedLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 排程控制器全局异常处理
 *
 * @author wengpc
 */
@Slf4j
@RestControllerAdvice(assignableTypes = LhScheduleResultController.class)
public class LhScheduleExceptionHandler {

    /**
     * 处理分布式锁获取失败异常，转为业务响应。
     */
    @ExceptionHandler(DistributedLockException.class)
    public LhScheduleResponseDTO handleDistributedLockException(DistributedLockException e) {
        log.warn("排程请求被拒绝: {}", e.getMessage());
        return LhScheduleResponseDTO.fail(null, e.getMessage());
    }
}
