-- ============================================
-- SYS0304023 迁移脚本
-- 目标：为现有工厂补齐“续作收尾小余量允许欠产偏差值”参数，默认值为 2
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
       'SYS0304023',
       '2',
       '续作收尾小余量允许欠产偏差值',
       '仅用于续作排产SKU收尾场景；硫化余量小于等于该值时本次不排产并释放原续作机台',
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
    AND p.PARAM_CODE = 'SYS0304023'
    AND p.IS_DELETE = 0
WHERE p.ID IS NULL;

UPDATE T_LH_PARAMS
SET PARAM_VALUE = '2',
    PARAM_NAME = '续作收尾小余量允许欠产偏差值',
    REMARK = '仅用于续作排产SKU收尾场景；硫化余量小于等于该值时本次不排产并释放原续作机台',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0304023'
  AND IS_DELETE = 0
  AND (PARAM_VALUE IS NULL OR PARAM_VALUE = '');
