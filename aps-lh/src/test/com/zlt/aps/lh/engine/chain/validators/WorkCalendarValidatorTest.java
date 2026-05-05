package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作日历校验器测试。
 *
 * @author APS
 */
class WorkCalendarValidatorTest {

    private final WorkCalendarValidator validator = new WorkCalendarValidator();

    @Test
    void validate_shouldFailWhenCalendarMissing() {
        LhScheduleContext context = buildContext();

        boolean valid = validator.validate(context);

        assertFalse(valid);
        assertTrue(context.getValidationErrorList().stream()
                .anyMatch(error -> error.contains("工作日历数据为空")));
    }

    @Test
    void validate_shouldFailWhenLhCalendarMissing() {
        LhScheduleContext context = buildContext();
        context.setWorkCalendarList(Collections.singletonList(calendar("01")));

        boolean valid = validator.validate(context);

        assertFalse(valid);
        assertTrue(context.getValidationErrorList().stream()
                .anyMatch(error -> error.contains("工作日历中无硫化工序")));
    }

    @Test
    void validate_shouldPassWhenLhCalendarExists() {
        LhScheduleContext context = buildContext();
        context.setWorkCalendarList(Collections.singletonList(calendar("02")));

        boolean valid = validator.validate(context);

        assertTrue(valid);
        assertTrue(context.getValidationErrorList().isEmpty());
    }

    /**
     * 构建基础排程上下文。
     *
     * @return 排程上下文
     */
    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setFactoryName("测试工厂");
        return context;
    }

    /**
     * 构建工作日历。
     *
     * @param procCode 工序编码
     * @return 工作日历
     */
    private MdmWorkCalendar calendar(String procCode) {
        MdmWorkCalendar calendar = new MdmWorkCalendar();
        calendar.setProcCode(procCode);
        return calendar;
    }
}
