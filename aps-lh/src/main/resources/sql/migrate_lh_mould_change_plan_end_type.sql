-- ============================================
-- T_LH_MOULD_CHANGE_PLAN 增加收尾类型字段
-- 目标：标识模具交替计划后规格物料是否为收尾SKU
-- endType取值：0-正常 1-收尾
-- ============================================

ALTER TABLE T_LH_MOULD_CHANGE_PLAN
    ADD COLUMN END_TYPE varchar(10) DEFAULT NULL COMMENT '收尾类型 0-正常 1-收尾'
    AFTER MOULD_STATUS;
