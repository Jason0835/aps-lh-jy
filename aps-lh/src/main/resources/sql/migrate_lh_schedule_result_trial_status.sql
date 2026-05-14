-- ============================================
-- T_LH_SCHEDULE_RESULT 增加产品状态字段
-- 目标：记录排程结果对应SKU的产品状态（X-试验示方 T-量试示方 S-正规示方）
-- ============================================

ALTER TABLE T_LH_SCHEDULE_RESULT
    ADD COLUMN TRIAL_STATUS varchar(30) DEFAULT NULL COMMENT '产品状态 X-试验示方 T-量试示方 S-正规示方'
    AFTER IS_TYPE_BLOCK;
