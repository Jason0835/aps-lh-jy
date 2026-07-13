package com.zlt.aps.lh.component;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhParams;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.mapper.LhParamsMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

/**
 * 硫化排程参数解析回归测试。
 */
class LhScheduleConfigResolverTest {

    @Test
    void resolveAndAttach_shouldUseDefaultEarlyProductionDaysWhenParamMissing() {
        LhScheduleConfigResolver resolver = resolverWithParam(null);
        LhScheduleContext context = context();

        resolver.resolveAndAttach(context);

        Assertions.assertEquals(LhScheduleConstant.DEFAULT_EARLY_PRODUCTION_DAYS_THRESHOLD,
                context.getScheduleConfig().getEarlyProductionDaysThreshold(),
                "提前生产天数阈值缺失时应使用默认值2");
    }

    @Test
    void resolveAndAttach_shouldUseDefaultEarlyProductionDaysWhenParamInvalidOrNonPositive() {
        LhScheduleConfigResolver invalidResolver = resolverWithParam(param("invalid"));
        LhScheduleContext invalidContext = context();
        invalidResolver.resolveAndAttach(invalidContext);

        LhScheduleConfigResolver zeroResolver = resolverWithParam(param("0"));
        LhScheduleContext zeroContext = context();
        zeroResolver.resolveAndAttach(zeroContext);

        Assertions.assertEquals(LhScheduleConstant.DEFAULT_EARLY_PRODUCTION_DAYS_THRESHOLD,
                invalidContext.getScheduleConfig().getEarlyProductionDaysThreshold(),
                "提前生产天数阈值格式非法时应使用默认值2");
        Assertions.assertEquals(LhScheduleConstant.DEFAULT_EARLY_PRODUCTION_DAYS_THRESHOLD,
                zeroContext.getScheduleConfig().getEarlyProductionDaysThreshold(),
                "提前生产天数阈值小于等于0时应使用默认值2");
    }

    @Test
    void resolveAndAttach_shouldCapEarlyProductionDaysAtOneMonth() {
        LhScheduleConfigResolver resolver = resolverWithParam(param("40"));
        LhScheduleContext context = context();

        resolver.resolveAndAttach(context);

        Assertions.assertEquals(LhScheduleConstant.MAX_EARLY_PRODUCTION_DAYS_THRESHOLD,
                context.getScheduleConfig().getEarlyProductionDaysThreshold(),
                "提前生产最多允许提前31个自然日");
    }

    @Test
    void resolveAndAttach_shouldResolveEndingAutoFillSwitchForZeroAndOne() {
        LhScheduleContext enabledContext = context();
        resolverWithParam(endingAutoFillParam("1")).resolveAndAttach(enabledContext);
        LhScheduleContext disabledContext = context();
        resolverWithParam(endingAutoFillParam("0")).resolveAndAttach(disabledContext);

        Assertions.assertTrue(enabledContext.getScheduleConfig().isEndingAutoFillEnabled(),
                "收尾自动补量参数为1时应开启");
        Assertions.assertFalse(disabledContext.getScheduleConfig().isEndingAutoFillEnabled(),
                "收尾自动补量参数为0时应关闭");
    }

    @Test
    void resolveAndAttach_shouldDefaultEndingAutoFillToEnabledWhenMissingEmptyOrInvalid() {
        Logger logger = (Logger) LoggerFactory.getLogger(LhScheduleConfigResolver.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        try {
            LhScheduleContext missingContext = context();
            resolverWithParam(null).resolveAndAttach(missingContext);
            LhScheduleContext emptyContext = context();
            resolverWithParam(endingAutoFillParam(" ")).resolveAndAttach(emptyContext);
            LhScheduleContext invalidTextContext = context();
            resolverWithParam(endingAutoFillParam("invalid")).resolveAndAttach(invalidTextContext);
            LhScheduleContext invalidNumberContext = context();
            resolverWithParam(endingAutoFillParam("2")).resolveAndAttach(invalidNumberContext);

            Assertions.assertTrue(missingContext.getScheduleConfig().isEndingAutoFillEnabled(),
                    "收尾自动补量参数缺失时应按默认1开启");
            Assertions.assertTrue(emptyContext.getScheduleConfig().isEndingAutoFillEnabled(),
                    "收尾自动补量参数为空时应按默认1开启");
            Assertions.assertTrue(invalidTextContext.getScheduleConfig().isEndingAutoFillEnabled(),
                    "收尾自动补量参数非数字时应按默认1开启");
            Assertions.assertTrue(invalidNumberContext.getScheduleConfig().isEndingAutoFillEnabled(),
                    "收尾自动补量参数非0/1时应按默认1开启");
            Assertions.assertTrue(listAppender.list.stream()
                            .anyMatch(event -> event.getFormattedMessage().contains("未配置或为空")),
                    "参数缺失或空值时应记录默认值告警");
            Assertions.assertTrue(listAppender.list.stream()
                            .anyMatch(event -> event.getFormattedMessage().contains("配置非法")),
                    "参数值非0/1时应记录默认值告警");
        } finally {
            logger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    private LhScheduleConfigResolver resolverWithParam(LhParams param) {
        LhScheduleConfigResolver resolver = new LhScheduleConfigResolver();
        LhParamsMapper mapper = Mockito.mock(LhParamsMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any())).thenReturn(
                param == null ? Collections.emptyList() : Collections.singletonList(param));
        ReflectionTestUtils.setField(resolver, "lhParamsMapper", mapper);
        return resolver;
    }

    private LhParams param(String value) {
        LhParams param = new LhParams();
        param.setFactoryCode("116");
        param.setParamCode(LhScheduleParamConstant.EARLY_PRODUCTION_DAYS_THRESHOLD);
        param.setParamValue(value);
        return param;
    }

    private LhParams endingAutoFillParam(String value) {
        LhParams param = new LhParams();
        param.setFactoryCode("116");
        param.setParamCode(LhScheduleParamConstant.ENDING_AUTO_FILL_ENABLED);
        param.setParamValue(value);
        return param;
    }

    private LhScheduleContext context() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        return context;
    }
}
