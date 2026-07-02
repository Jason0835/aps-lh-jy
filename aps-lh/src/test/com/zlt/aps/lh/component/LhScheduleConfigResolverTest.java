package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhParams;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.mapper.LhParamsMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
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

    private LhScheduleContext context() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        return context;
    }
}
