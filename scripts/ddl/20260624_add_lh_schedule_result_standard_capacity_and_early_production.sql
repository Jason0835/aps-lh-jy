-- ============================================================
-- 变更：硫化排程结果表新增 日标准产量 / SKU 提前生产标识
-- 表  ：T_LH_SCHEDULE_RESULT
-- 日期：2026-06-24
-- 说明：
--   1. STANDARD_CAPACITY 来源 T_MDM_SKU_LH_CAPACITY.STANDARD_CAPACITY，
--      由排程引擎 ShiftCapacityResolverUtil#resolveDailyStandardQty 解析，
--      无主数据时落 0。
--   2. IS_EARLY_PRODUCTION 仅 NewSpecProductionStrategy 新增（02）结果在
--      命中 EarlyProductionDecision.earlyProduction && allowed 时回写为 '1'，
--      与提前生产备注 [结构切换]/[结构收尾] 同源；
--      续作（01）、换活字块（03）、滚动继承结果一律为 '0'。
-- ============================================================
ALTER TABLE `T_LH_SCHEDULE_RESULT`
    ADD COLUMN `STANDARD_CAPACITY` INT DEFAULT NULL
        COMMENT '日标准产量(来源 T_MDM_SKU_LH_CAPACITY.STANDARD_CAPACITY)'
        AFTER `TOTAL_FINISH_QTY`,
    ADD COLUMN `IS_EARLY_PRODUCTION` CHAR(1) DEFAULT '0'
        COMMENT 'SKU 提前生产标识 0-否 1-是'
        AFTER `STANDARD_CAPACITY`;

-- 历史数据兜底：将历史行未设置的提前生产标识统一回填为 '0'
UPDATE `T_LH_SCHEDULE_RESULT`
   SET `IS_EARLY_PRODUCTION` = '0'
 WHERE `IS_EARLY_PRODUCTION` IS NULL;
