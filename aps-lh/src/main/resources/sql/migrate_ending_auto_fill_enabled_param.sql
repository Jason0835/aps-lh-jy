-- ============================================
-- SYS0304029 迁移脚本
-- 目标：为现有工厂补齐“收尾落在夜班或错开模具交替是否自动补量”参数，默认值为1
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
       'SYS0304029',
       '1',
       '收尾落在夜班或错开模具交替是否自动补量',
       '0-否，1-是；只控制主销/常规SKU收尾补满和共用胎胚SKU收尾错峰后延补量',
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
    AND p.PARAM_CODE = 'SYS0304029'
    AND p.IS_DELETE = 0
WHERE p.ID IS NULL;

UPDATE T_LH_PARAMS
SET PARAM_VALUE = '1',
    PARAM_NAME = '收尾落在夜班或错开模具交替是否自动补量',
    REMARK = '0-否，1-是；只控制主销/常规SKU收尾补满和共用胎胚SKU收尾错峰后延补量',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0304029'
  AND IS_DELETE = 0
  AND (PARAM_VALUE IS NULL OR PARAM_VALUE = '');
