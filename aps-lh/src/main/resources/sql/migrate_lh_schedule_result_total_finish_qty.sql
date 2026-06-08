-- ============================================
-- T_LH_SCHEDULE_RESULT 增加月累计已完成量字段
-- 目标：记录排程结果对应 SKU 的月累计已完成量（截至排程窗口 T-1 日 + T 日班次完成量）
-- ============================================

ALTER TABLE T_LH_SCHEDULE_RESULT
    ADD COLUMN TOTAL_FINISH_QTY int(11) DEFAULT NULL COMMENT '月累计已完成量'
    AFTER SHORTAGE_QTY;
