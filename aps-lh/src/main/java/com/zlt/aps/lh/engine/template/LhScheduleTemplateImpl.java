package com.zlt.aps.lh.engine.template;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.ContinuousProductionHandler;
import com.zlt.aps.lh.handler.DataInitHandler;
import com.zlt.aps.lh.handler.NewProductionHandler;
import com.zlt.aps.lh.handler.PreValidationHandler;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 硫化排程模板方法具体实现。
 *
 * <p>该类只负责把模板步骤绑定到实际 Handler：</p>
 * <ul>
 *   <li>{@link PreValidationHandler}：前置校验和批次号生成；</li>
 *   <li>{@link DataInitHandler}：基础数据、班次窗口和机台状态初始化；</li>
 *   <li>{@link ScheduleAdjustHandler}：月计划 SKU 归集、欠产传导、收尾和续作/新增分类；</li>
 *   <li>{@link ContinuousProductionHandler}：续作、换活字块、续作降模；</li>
 *   <li>{@link NewProductionHandler}：新增 SKU 选机、换模、首检、班次分配；</li>
 *   <li>{@link ResultValidationHandler}：后置校验、换模计划、工单号和结果保存。</li>
 * </ul>
 *
 * <p>注意：该类不直接实现排程规则，新增规则应优先放入对应 Handler 或策略类。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class LhScheduleTemplateImpl extends AbsLhScheduleTemplate {

    @Resource
    private PreValidationHandler preValidationHandler;

    @Resource
    private DataInitHandler dataInitHandler;

    @Resource
    private ScheduleAdjustHandler scheduleAdjustHandler;

    @Resource
    private ContinuousProductionHandler continuousProductionHandler;

    @Resource
    private NewProductionHandler newProductionHandler;

    @Resource
    private ResultValidationHandler resultValidationHandler;

    @Override
    protected void doPreValidation(LhScheduleContext context) {
        preValidationHandler.handle(context);
    }

    @Override
    protected void doDataInitialization(LhScheduleContext context) {
        dataInitHandler.handle(context);
    }

    @Override
    protected void doAdjustAndGather(LhScheduleContext context) {
        scheduleAdjustHandler.handle(context);
    }

    @Override
    protected void doContinuousProduction(LhScheduleContext context) {
        continuousProductionHandler.handle(context);
    }

    @Override
    protected void doNewSpecProduction(LhScheduleContext context) {
        newProductionHandler.handle(context);
    }

    @Override
    protected void doResultValidationAndSave(LhScheduleContext context) {
        resultValidationHandler.handle(context);
    }
}
