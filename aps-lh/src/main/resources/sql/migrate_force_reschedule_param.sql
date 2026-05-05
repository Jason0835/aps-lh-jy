-- ============================================
-- FORCE_RESCHEDULE 迁移脚本
-- 目标：为现有工厂补齐“是否强制重排”参数，默认值为 1
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
       'FORCE_RESCHEDULE',
       '1',
       '是否强制重排',
       '0-否，走滚动衔接；1-是，窗口内全部重排',
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
    AND p.PARAM_CODE = 'FORCE_RESCHEDULE'
    AND p.IS_DELETE = 0
WHERE p.ID IS NULL;

UPDATE T_LH_PARAMS
SET PARAM_VALUE = '1',
    PARAM_NAME = '是否强制重排',
    REMARK = '0-否，走滚动衔接；1-是，窗口内全部重排',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'FORCE_RESCHEDULE'
  AND IS_DELETE = 0
  AND (PARAM_VALUE IS NULL OR PARAM_VALUE = '');
