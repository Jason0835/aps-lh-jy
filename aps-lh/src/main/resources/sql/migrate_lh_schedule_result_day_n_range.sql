-- ============================================
-- T_LH_SCHEDULE_RESULT 增加 T~T+2 日计划量字段
-- 目标：记录排程结果对应月计划 T~T+2 的 dayN 值，多个以逗号分隔
-- ============================================

ALTER TABLE T_LH_SCHEDULE_RESULT
    ADD COLUMN DAY_N_RANGE varchar(100) DEFAULT NULL COMMENT 'T~T+2日计划量，多个以逗号分隔，来源于月计划 dayN'
    AFTER MOULD_CHANGE_START_TIME;
