-- ============================================
-- SYS0303003 迁移脚本
-- 目标：为现有工厂补齐“首检数量”参数，默认值为 4
-- ============================================

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
SELECT t.FACTORY_CODE,
       'SYS0303003',
       '4',
       '首检数量',
       '普通换模8小时已包含首检，首检数量只影响班次计划量归属和班产占用',
       'system',
       NOW(),
       'system',
       NOW(),
       0
FROM (
    SELECT DISTINCT FACTORY_CODE
    FROM T_LH_PARAMS
    WHERE IS_DELETE = 0
      AND FACTORY_CODE IS NOT NULL
      AND FACTORY_CODE <> ''
) t
LEFT JOIN T_LH_PARAMS p
    ON p.FACTORY_CODE = t.FACTORY_CODE
    AND p.PARAM_CODE = 'SYS0303003'
    AND p.IS_DELETE = 0
WHERE p.ID IS NULL;

UPDATE T_LH_PARAMS
SET PARAM_VALUE = '4',
    PARAM_NAME = '首检数量',
    REMARK = '普通换模8小时已包含首检，首检数量只影响班次计划量归属和班产占用',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0303003'
  AND IS_DELETE = 0
  AND (PARAM_VALUE IS NULL OR PARAM_VALUE = '');
