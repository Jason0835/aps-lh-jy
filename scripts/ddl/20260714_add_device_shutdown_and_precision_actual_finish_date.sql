-- ============================================================
-- 变更：设备计划停机、硫化精度计划新增实际完成时间
-- 表  ：T_MDM_DEVICE_PLAN_SHUT、T_LH_PRECISION_PLAN
-- 日期：2026-07-14
-- 说明：
--   由设备或 MES 业务端在停机、精度计划实际完成后回写。
--   硫化排程仅加载 ACTUAL_FINISH_DATE 为空的记录，避免已完成计划重复占用机台产能。
-- ============================================================
ALTER TABLE `T_MDM_DEVICE_PLAN_SHUT`
    ADD COLUMN `ACTUAL_FINISH_DATE` DATETIME NULL
        COMMENT '实际完成时间'
        AFTER `END_DATE`;

ALTER TABLE `T_LH_PRECISION_PLAN`
    ADD COLUMN `ACTUAL_FINISH_DATE` DATETIME NULL
        COMMENT '实际完成时间'
        AFTER `ACTUAL_DATE`;
