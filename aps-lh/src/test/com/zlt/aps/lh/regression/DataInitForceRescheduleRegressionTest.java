package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.ValidationResult;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.chain.DataValidationChain;
import com.zlt.aps.lh.engine.strategy.IProductionShutdownStrategy;
import com.zlt.aps.lh.handler.DataInitHandler;
import com.zlt.aps.lh.service.ILhBaseDataService;
import com.zlt.aps.lh.service.ILhShiftConfigService;
import com.zlt.aps.lh.service.impl.RollingScheduleHandoffService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 强制重排模式回归测试。
 */
@ExtendWith(MockitoExtension.class)
class DataInitForceRescheduleRegressionTest {

    private static final String YES = "1";
    private static final String NO = "0";

    private DataInitHandler handler;

    @Mock
    private DataValidationChain dataValidationChain;

    @Mock
    private ILhBaseDataService baseDataService;

    @Mock
    private ILhShiftConfigService lhShiftConfigService;

    @Mock
    private RollingScheduleHandoffService rollingScheduleHandoffService;

    @Mock
    private IProductionShutdownStrategy productionShutdownStrategy;

    @BeforeEach
    void setUp() {
        handler = new DataInitHandler();
        ReflectionTestUtils.setField(handler, "dataValidationChain", dataValidationChain);
        ReflectionTestUtils.setField(handler, "baseDataService", baseDataService);
        ReflectionTestUtils.setField(handler, "lhShiftConfigService", lhShiftConfigService);
        ReflectionTestUtils.setField(handler, "rollingScheduleHandoffService", rollingScheduleHandoffService);
        ReflectionTestUtils.setField(handler, "productionShutdownStrategy", productionShutdownStrategy);
        when(dataValidationChain.validateWithResult(any())).thenReturn(ValidationResult.pass());
    }

    @Test
    void handle_shouldSkipRollingHandoffWhenForceRescheduleEnabled() {
        LhScheduleContext context = buildContext(YES);

        handler.handle(context);

        verify(rollingScheduleHandoffService, never()).apply(any());
    }

    @Test
    void handle_shouldApplyRollingHandoffWhenForceRescheduleDisabled() {
        LhScheduleContext context = buildContext(NO);

        handler.handle(context);

        verify(rollingScheduleHandoffService).apply(context);
    }

    @Test
    void handle_shouldSkipRollingHandoffWhenForceRescheduleMissing() {
        LhScheduleContext context = buildContext(null);

        handler.handle(context);

        verify(rollingScheduleHandoffService, never()).apply(any());
    }

    private LhScheduleContext buildContext(String forceReschedule) {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(LhScheduleTimeUtil.clearTime(new java.util.Date()));
        context.setScheduleTargetDate(context.getScheduleDate());
        if (Objects.isNull(forceReschedule)) {
            context.setScheduleConfig(new LhScheduleConfig(Collections.emptyMap()));
        } else {
            context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, forceReschedule);
            context.setScheduleConfig(new LhScheduleConfig(
                    Collections.singletonMap(LhScheduleParamConstant.FORCE_RESCHEDULE, forceReschedule)));
        }
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        return context;
    }
}
