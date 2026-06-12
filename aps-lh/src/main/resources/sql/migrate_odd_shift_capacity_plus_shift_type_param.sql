-- 奇数班产计划量加一班别参数迁移脚本
-- 默认空值表示不启用；配置 1-晚班+1，2-早班+1，3-中班+1。

INSERT INTO T_LH_PARAMS (
    FACTORY_CODE,
    PARAM_CODE,
    PARAM_VALUE,
    PARAM_NAME,
    REMARK,
    IS_DELETE
)
SELECT
    '116',
    'SYS0304024',
    '',
    '奇数班产计划量加一班别',
    '空值不启用；1-晚班+1，2-早班+1，3-中班+1；仅新增、续作、换活字块排产生效，班产落库字段保持原始班产',
    0
WHERE NOT EXISTS (
    SELECT 1
    FROM T_LH_PARAMS p
    WHERE p.FACTORY_CODE = '116'
      AND p.PARAM_CODE = 'SYS0304024'
      AND p.IS_DELETE = 0
);
