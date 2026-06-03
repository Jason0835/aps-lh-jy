-- ============================================
-- T_LH_SCHEDULE_RESULT 增加月初至 T-1 日累计欠产量字段
-- 目标：记录排程结果对应 SKU 从月初到 T-1 日的累计欠产量
-- ============================================

ALTER TABLE T_LH_SCHEDULE_RESULT
    ADD COLUMN SHORTAGE_QTY int(11) DEFAULT NULL COMMENT '月初至 T-1 日累计欠产量'
    AFTER DAY_N_RANGE;
