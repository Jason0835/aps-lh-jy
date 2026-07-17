-- ============================================================
-- SYS0309002 / SYS0309003 换胶囊规则参数迁移脚本
-- 目标：为现有工厂补齐胶囊使用上限和换胶囊班次扣减量，并更新参数说明。
-- 说明：脚本不覆盖已有有效配置值，允许各工厂继续使用自己的上限和扣减量。
-- ============================================================

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
SELECT factory.FACTORY_CODE,
       param.PARAM_CODE,
       param.PARAM_VALUE,
       param.PARAM_NAME,
       param.REMARK,
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
    UNION
    SELECT '116'
) factory
CROSS JOIN (
    SELECT 'SYS0309002' AS PARAM_CODE,
           '450' AS PARAM_VALUE,
           '胶囊使用次数上限' AS PARAM_NAME,
           '当前次数加扣减前实际可排量严格大于上限时换胶囊，刚好达到不触发' AS REMARK
    UNION ALL
    SELECT 'SYS0309003',
           '2',
           '换胶囊班次扣减计划量',
           '换胶囊固定占用1小时，默认扣减2条班次计划量'
) param
LEFT JOIN T_LH_PARAMS existing
    ON existing.FACTORY_CODE = factory.FACTORY_CODE
    AND existing.PARAM_CODE = param.PARAM_CODE
    AND existing.IS_DELETE = 0
WHERE existing.ID IS NULL;

-- 仅同步参数名称和业务说明，禁止覆盖现有工厂已经配置的参数值。
UPDATE T_LH_PARAMS
SET PARAM_NAME = '胶囊使用次数上限',
    REMARK = '当前次数加扣减前实际可排量严格大于上限时换胶囊，刚好达到不触发',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0309002'
  AND IS_DELETE = 0;

UPDATE T_LH_PARAMS
SET PARAM_NAME = '换胶囊班次扣减计划量',
    REMARK = '换胶囊固定占用1小时，默认扣减2条班次计划量',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0309003'
  AND IS_DELETE = 0;
