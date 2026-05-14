package com.zlt.aps.lh.component;

import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排程批次号 Redis 生成器测试。
 */
@ExtendWith(MockitoExtension.class)
class LhBatchNoRedisGeneratorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LhBatchNoRedisGenerator generator;

    @Test
    void nextBatchNo_shouldShareSequenceAcrossFactoriesForSameTargetDate() {
        Date scheduleDate = date(2026, 5, 6);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("aps:lh:batch:no:20260506")).thenReturn(1L, 2L);

        String firstBatchNo = generator.nextBatchNo(scheduleDate, "116");
        String secondBatchNo = generator.nextBatchNo(scheduleDate, "117");

        assertEquals("LHPC20260506001", firstBatchNo);
        assertEquals("LHPC20260506002", secondBatchNo);
        verify(valueOperations, times(2)).increment("aps:lh:batch:no:20260506");
        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void nextBatchNo_shouldRestartSequenceForDifferentTargetDate() {
        Date firstDate = date(2026, 5, 6);
        Date secondDate = date(2026, 5, 7);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("aps:lh:batch:no:20260506")).thenReturn(1L);
        when(valueOperations.increment("aps:lh:batch:no:20260507")).thenReturn(1L);

        String firstBatchNo = generator.nextBatchNo(firstDate, "116");
        String secondBatchNo = generator.nextBatchNo(secondDate, "116");

        assertEquals("LHPC20260506001", firstBatchNo);
        assertEquals("LHPC20260507001", secondBatchNo);
    }

    @Test
    void nextBatchNo_shouldNotSetExpireWhenFirstSequenceGenerated() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("aps:lh:batch:no:20260506")).thenReturn(1L);

        String batchNo = generator.nextBatchNo(date(2026, 5, 6), "116");

        assertEquals("LHPC20260506001", batchNo);
        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void nextBatchNo_shouldThrowWhenRedisIncrementReturnsNull() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("aps:lh:batch:no:20260506")).thenReturn(null);

        ScheduleException exception = assertThrows(ScheduleException.class,
                () -> generator.nextBatchNo(date(2026, 5, 6), "116"));

        assertEquals(ScheduleErrorCode.BATCH_NO_GENERATE_FAILED, exception.getErrorCode());
    }

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }
}
