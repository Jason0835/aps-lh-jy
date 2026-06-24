-- ============================================================
-- 变更：硫化排程结果表新增 SKU 排序信息与三个机台数串字段
-- 表  ：T_LH_SCHEDULE_RESULT
-- 日期：2026-06-24
-- 说明：
--   1. SKU_SORT_RANK / SKU_SORT_DESC 来源 DefaultSkuPriorityStrategy
--      回写到 SkuScheduleDTO 的 sortRank / sortDesc，与
--      "SKU排序优先级汇总【新增】/【续作】" 日志同源；排程结果通过
--      LhScheduleContext.scheduleResultSourceSkuMap 按对象身份取来源
--      SKU 后落库；滚动继承结果保留原值不覆盖。
--   2. STRUCTURE_PLAN_MACHINE_COUNT_RANGE / STRUCTURE_SCHEDULED_MACHINE_COUNT_RANGE
--      / SKU_SCHEDULED_MACHINE_COUNT_RANGE 来源 LhScheduleContext 的
--      structurePlanMachineCountMap / structureScheduledMachineCodeMap /
--      skuScheduledMachineCodeMap，格式 'T=2,T+1=2,T+2=3'，与 dayNRange 同窗口。
-- ============================================================
ALTER TABLE `T_LH_SCHEDULE_RESULT`
    ADD COLUMN `SKU_SORT_RANK` INT DEFAULT NULL
        COMMENT 'SKU排序名次(续作/新增列表内 1~N)'
        AFTER `IS_EARLY_PRODUCTION`,
    ADD COLUMN `SKU_SORT_DESC` VARCHAR(1000) DEFAULT NULL
        COMMENT 'SKU排序描述(与SKU排序优先级汇总日志单行同源)'
        AFTER `SKU_SORT_RANK`,
    ADD COLUMN `STRUCTURE_PLAN_MACHINE_COUNT_RANGE` VARCHAR(64) DEFAULT NULL
        COMMENT '结构计划硫化机台数(格式 T=2,T+1=2,T+2=3)'
        AFTER `SKU_SORT_DESC`,
    ADD COLUMN `STRUCTURE_SCHEDULED_MACHINE_COUNT_RANGE` VARCHAR(64) DEFAULT NULL
        COMMENT '结构已排硫化机台数(格式 T=2,T+1=2,T+2=3)'
        AFTER `STRUCTURE_PLAN_MACHINE_COUNT_RANGE`,
    ADD COLUMN `SKU_SCHEDULED_MACHINE_COUNT_RANGE` VARCHAR(64) DEFAULT NULL
        COMMENT 'SKU已排硫化机台数(格式 T=2,T+1=2,T+2=3)'
        AFTER `STRUCTURE_SCHEDULED_MACHINE_COUNT_RANGE`;
