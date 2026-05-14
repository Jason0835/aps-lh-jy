-- ============================================
-- 试制量试、小批量与单控机台参数迁移脚本
-- 目标：为 116 工厂补齐单控基准机台与小批量阈值，并明确试制量试每日限制语义
-- ============================================

UPDATE T_LH_PARAMS
SET PARAM_NAME = '试制量试每日不同物料数上限',
    REMARK = '每天最多 2 个不同试制/量试物料',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE FACTORY_CODE = '116'
  AND PARAM_CODE = 'TRIAL_DAILY_LIMIT'
  AND IS_DELETE = 0;

INSERT INTO T_LH_PARAMS (
    FACTORY_CODE,
    PARAM_CODE,
    PARAM_VALUE,
    PARAM_NAME,
    REMARK,
    CREATE_BY,
    CREATE_TIME,
    UPDATE_BY,
    UPDATE_TIME,
    IS_DELETE
)
SELECT '116',
       'SINGLE_CONTROL_MACHINE_CODES',
       'K1501,K1502',
       '单控基准机台编码',
       '多个机台用逗号分隔，运行态拆分为左右单模机台',
       'system',
       NOW(),
       'system',
       NOW(),
       0
WHERE NOT EXISTS (
    SELECT 1
    FROM T_LH_PARAMS
    WHERE FACTORY_CODE = '116'
      AND PARAM_CODE = 'SINGLE_CONTROL_MACHINE_CODES'
      AND IS_DELETE = 0
);

UPDATE T_LH_PARAMS
SET PARAM_VALUE = 'K1501,K1502',
    PARAM_NAME = '单控基准机台编码',
    REMARK = '多个机台用逗号分隔，运行态拆分为左右单模机台',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE FACTORY_CODE = '116'
  AND PARAM_CODE = 'SINGLE_CONTROL_MACHINE_CODES'
  AND IS_DELETE = 0
  AND (PARAM_VALUE IS NULL OR PARAM_VALUE = '');

INSERT INTO T_LH_PARAMS (
    FACTORY_CODE,
    PARAM_CODE,
    PARAM_VALUE,
    PARAM_NAME,
    REMARK,
    CREATE_BY,
    CREATE_TIME,
    UPDATE_BY,
    UPDATE_TIME,
    IS_DELETE
)
SELECT '116',
       'SMALL_BATCH_SKU_THRESHOLD',
       '100',
       '小批量验证SKU阈值',
       'TRIAL_QTY 大于0且小于该值时识别为小批量验证SKU',
       'system',
       NOW(),
       'system',
       NOW(),
       0
WHERE NOT EXISTS (
    SELECT 1
    FROM T_LH_PARAMS
    WHERE FACTORY_CODE = '116'
      AND PARAM_CODE = 'SMALL_BATCH_SKU_THRESHOLD'
      AND IS_DELETE = 0
);

UPDATE T_LH_PARAMS
SET PARAM_VALUE = '100',
    PARAM_NAME = '小批量验证SKU阈值',
    REMARK = 'TRIAL_QTY 大于0且小于该值时识别为小批量验证SKU',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE FACTORY_CODE = '116'
  AND PARAM_CODE = 'SMALL_BATCH_SKU_THRESHOLD'
  AND IS_DELETE = 0
  AND (PARAM_VALUE IS NULL OR PARAM_VALUE = '');
