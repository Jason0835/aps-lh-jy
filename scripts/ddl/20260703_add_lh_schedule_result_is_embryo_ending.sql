-- ============================================================
-- 变更：硫化排程结果表新增 胎胚收尾标识字段
-- 表  ：T_LH_SCHEDULE_RESULT
-- 日期：2026-07-03
-- 说明：
--   IS_EMBRYO_ENDING 取值来源 LhScheduleContext.embryoEndingFlagMap
--   （key=胎胚代码, value=1-胎胚收尾/0-胎胚非收尾），
--   由 S4.6 保存前 SchedulePersistenceService.fillEmbryoEndingAnalysis 统一回写。
-- ============================================================
ALTER TABLE `T_LH_SCHEDULE_RESULT`
    ADD COLUMN `IS_EMBRYO_ENDING` CHAR(1) DEFAULT '0'
        COMMENT '胎胚收尾标识 0-否 1-是'
        AFTER `IS_EARLY_PRODUCTION`;

-- 历史数据兜底：将历史行未设置的胎胚收尾标识统一回填为 '0'
UPDATE `T_LH_SCHEDULE_RESULT`
   SET `IS_EMBRYO_ENDING` = '0'
 WHERE `IS_EMBRYO_ENDING` IS NULL;
