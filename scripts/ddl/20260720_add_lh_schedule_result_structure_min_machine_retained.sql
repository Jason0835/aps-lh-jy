-- ============================================================
-- 变更：硫化排程结果表新增“结构最低机台数保留”标识
-- 表  ：T_LH_SCHEDULE_RESULT
-- 日期：2026-07-20
-- 说明：
--   同结构全部SKU可在当前3天、8班窗口内收尾，且结构最晚有量班次的
--   去重物理机台数小于结构最低硫化机台数时，该结构本窗口全部结果标记为1。
--   提前收尾机台仍复用原结果行补计划量0、班次起止时间和占位备注，不新增结果行。
-- ============================================================
ALTER TABLE `T_LH_SCHEDULE_RESULT`
    ADD COLUMN `IS_STRUCTURE_MIN_MACHINE_RETAINED` CHAR(1) NOT NULL DEFAULT '0'
        COMMENT '结构最低机台数保留标识 0-否 1-是'
        AFTER `IS_EMBRYO_ENDING`;

-- 历史数据不属于本次运行态保留，统一按未命中处理。
UPDATE `T_LH_SCHEDULE_RESULT`
   SET `IS_STRUCTURE_MIN_MACHINE_RETAINED` = '0'
 WHERE `IS_STRUCTURE_MIN_MACHINE_RETAINED` IS NULL;
