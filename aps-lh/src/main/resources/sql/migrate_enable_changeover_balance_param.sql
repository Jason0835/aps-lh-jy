-- ============================================
-- SYS0304021 迁移脚本
-- 目标：为现有工厂补齐“是否开启换模均衡”参数，默认值为 0
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
       'SYS0304021',
       '0',
       '是否开启换模均衡',
       '0-关闭，1-开启；关闭后新增排产只保留基础换模耗时与晚班不可换模约束，不再校验换模均衡配额',
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
    AND p.PARAM_CODE = 'SYS0304021'
    AND p.IS_DELETE = 0
WHERE p.ID IS NULL;

UPDATE T_LH_PARAMS
SET PARAM_VALUE = '0',
    PARAM_NAME = '是否开启换模均衡',
    REMARK = '0-关闭，1-开启；关闭后新增排产只保留基础换模耗时与晚班不可换模约束，不再校验换模均衡配额',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0304021'
  AND IS_DELETE = 0
  AND (PARAM_VALUE IS NULL OR PARAM_VALUE = '');
