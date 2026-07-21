-- ============================================================
-- SYS0309002 / SYS0309003 胶囊首次严格跨限规则参数迁移脚本
-- 目标：为现有工厂补齐胶囊使用上限和首次跨限扣减量，并更新参数说明。
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
           '本批初值取左右最大值，按物理机台总产量累计；仅首次严格超过上限时触发扣量' AS REMARK
    UNION ALL
    SELECT 'SYS0309003',
           '2',
           '换胶囊班次扣减计划量',
           '本批首次严格跨限固定占用1小时，默认扣减2条；后续班次不再扣减'
) param
LEFT JOIN T_LH_PARAMS existing
    ON existing.FACTORY_CODE = factory.FACTORY_CODE
    AND existing.PARAM_CODE = param.PARAM_CODE
    AND existing.IS_DELETE = 0
WHERE existing.ID IS NULL;

-- 仅同步参数名称和业务说明，禁止覆盖现有工厂已经配置的参数值。
UPDATE T_LH_PARAMS
SET PARAM_NAME = '胶囊使用次数上限',
    REMARK = '本批初值取左右最大值，按物理机台总产量累计；仅首次严格超过上限时触发扣量',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0309002'
  AND IS_DELETE = 0;

UPDATE T_LH_PARAMS
SET PARAM_NAME = '换胶囊班次扣减计划量',
    REMARK = '本批首次严格跨限固定占用1小时，默认扣减2条；后续班次不再扣减',
    UPDATE_BY = 'system',
    UPDATE_TIME = NOW()
WHERE PARAM_CODE = 'SYS0309003'
  AND IS_DELETE = 0;
