package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试制量试示方校验测试。
 */
class TrialFormulaValidatorTest {

    private final TrialFormulaValidator validator = new TrialFormulaValidator();

    @Test
    void validate_shouldRejectTrialPlanWhenFormulaMissing() {
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult plan = trialPlan("3302001575");
        plan.setEmbryoNo("");
        plan.setTextNo("TEXT-01");
        plan.setLhNo(null);
        context.setMonthPlanList(Collections.singletonList(plan));

        boolean passed = validator.validate(context);

        assertFalse(passed);
        assertTrue(context.getValidationErrorList().get(0).contains("3302001575"));
        assertTrue(context.getValidationErrorList().get(0).contains("制造示方"));
        assertTrue(context.getValidationErrorList().get(0).contains("硫化示方"));
    }

    @Test
    void validate_shouldIgnoreNormalPlanWithoutTrialDemand() {
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult plan = trialPlan("3302001418");
        plan.setConstructionStage("03");
        plan.setTrialQty(0);
        context.setMonthPlanList(Collections.singletonList(plan));

        assertTrue(validator.validate(context));
        assertTrue(context.getValidationErrorList().isEmpty());
    }

    private FactoryMonthPlanProductionFinalResult trialPlan(String materialCode) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setConstructionStage("01");
        plan.setTrialQty(10);
        plan.setEmbryoNo("EMB-01");
        plan.setTextNo("TEXT-01");
        plan.setLhNo("LH-01");
        return plan;
    }
}
