package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工单号生成器（线程安全）
 * <p>
 * 支持两种模式：
 * 1. Redis模式（推荐）：使用Redis原子递增，支持分布式环境
 * 2. 本地模式：使用AtomicInteger，仅支持单机环境
 * </p>
 *
 * @author APS
 */
@Slf4j
@Component
public class OrderNoGenerator {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** Redis Key前缀 */
    private static final String REDIS_KEY_PREFIX = "APS:LH:ORDER:";

    /** 本地序列号（降级方案） */
    private static final AtomicInteger localSequence = new AtomicInteger(0);

    /** 是否使用Redis（可通过配置切换） */
    private volatile boolean useRedis = true;

    /**
     * 生成排程工单号
     * <p>格式：LHGD + yyyyMMdd + 3位流水号</p>
     *
     * @param targetDate 目标日期
     * @return 工单号
     */
    public String generateOrderNo(Date targetDate) {
        return generate(LhScheduleConstant.ORDER_NO_PREFIX, targetDate);
    }

    /**
     * 生成换模工单号
     * <p>格式：CHG + yyyyMMdd + 3位流水号</p>
     *
     * @param targetDate 目标日期
     * @return 工单号
     */
    public String generateMouldChangeOrderNo(Date targetDate) {
        return generate(LhScheduleConstant.MOULD_CHANGE_ORDER_PREFIX, targetDate);
    }

    /**
     * 生成批次号
     * <p>格式：LHPC + yyyyMMdd + 3位流水号</p>
     *
     * @param targetDate 目标日期
     * @param factoryCode 工厂编码
     * @return 批次号
     */
    public String generateBatchNo(Date targetDate, String factoryCode) {
        return generate(LhScheduleConstant.BATCH_NO_PREFIX, targetDate);
    }

    /**
     * 通用生成方法
     *
     * @param prefix 前缀
     * @param targetDate 目标日期
     * @return 序列号
     */
    private String generate(String prefix, Date targetDate) {
        // TODO 后续可统一替换为 Hutool 或 java.time 格式化，当前保持历史批次号/工单号格式不变。
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(targetDate);
        int seq = getNextSequence(prefix, dateStr);
        return String.format("%s%s%03d", prefix, dateStr, seq % 1000);
    }

    /**
     * 获取下一个序列号（线程安全）
     *
     * @param prefix 前缀
     * @param dateStr 日期字符串
     * @return 序列号
     */
    private int getNextSequence(String prefix, String dateStr) {
        if (useRedis) {
            try {
                String redisKey = REDIS_KEY_PREFIX + prefix + dateStr;
                Long seq = stringRedisTemplate.opsForValue().increment(redisKey);
                // 设置24小时过期
                stringRedisTemplate.expire(redisKey, 24, TimeUnit.HOURS);
                return seq != null ? seq.intValue() : 1;
            } catch (Exception e) {
                log.warn("Redis生成序号失败，降级为本地模式: {}", e.getMessage());
                useRedis = false;
            }
        }
        // 本地模式降级
        return localSequence.incrementAndGet();
    }

    /**
     * 重置Redis模式（用于Redis恢复后重新启用）
     */
    public void resetRedisMode() {
        this.useRedis = true;
        log.info("工单号生成器已切换回Redis模式");
    }
}
