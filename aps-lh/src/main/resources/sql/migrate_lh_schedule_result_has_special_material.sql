-- ============================================
-- T_LH_SCHEDULE_RESULT 增加是否含特殊材料字段
-- 目标：记录排程结果对应SKU是否含特殊材料，默认 0-否
-- ============================================

ALTER TABLE T_LH_SCHEDULE_RESULT
    ADD COLUMN HAS_SPECIAL_MATERIAL varchar(10) DEFAULT '0' COMMENT '是否含特殊材料 0-否 1-是'
    AFTER MOULD_CODE;
