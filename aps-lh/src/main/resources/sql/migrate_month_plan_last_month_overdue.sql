-- ============================================
-- T_MP_MONTH_PLAN_PROD_FINAL 增加上月超欠产字段
-- 目标：支持上月超欠产数量参与硫化余量计算
-- ============================================

ALTER TABLE T_MP_MONTH_PLAN_PROD_FINAL
    ADD COLUMN LAST_MONTH_VALID_FLAG char(1) DEFAULT NULL COMMENT '上月超欠产是否有效，1-有效，其他-无效'
    AFTER LAST_MONTH_PLAN_VERSION;

ALTER TABLE T_MP_MONTH_PLAN_PROD_FINAL
    ADD COLUMN LAST_MONTH_OVERDUE_QTY decimal(8,0) DEFAULT NULL COMMENT '上月超欠产数量，仅在上月超欠产有效时参与硫化余量计算'
    AFTER LAST_MONTH_VALID_FLAG;
