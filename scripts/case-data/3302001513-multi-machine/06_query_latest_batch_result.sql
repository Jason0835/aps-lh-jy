SELECT batch_no,
       COUNT(*) AS result_count,
       SUM(daily_plan_qty) AS total_plan_qty,
       MAX(create_time) AS latest_create_time
FROM t_lh_schedule_result
WHERE factory_code='116'
  AND schedule_date='2026-05-03'
GROUP BY batch_no
ORDER BY latest_create_time DESC
LIMIT 5;

SELECT batch_no,
       material_code,
       lh_machine_code,
       daily_plan_qty,
       class1_plan_qty,
       class2_plan_qty,
       class3_plan_qty,
       class4_plan_qty,
       class5_plan_qty,
       class6_plan_qty,
       class7_plan_qty,
       class8_plan_qty,
       is_end,
       schedule_type
FROM t_lh_schedule_result
WHERE batch_no='请替换最新batchNo'
  AND material_code='3302001513'
ORDER BY lh_machine_code;

SELECT create_time,
       log_content
FROM t_lh_schedule_process_log
WHERE batch_no='请替换最新batchNo'
  AND (
        log_content LIKE '%3302001513%'
        OR log_content LIKE '%续作多机台降模结果%'
        OR log_content LIKE '%续作多机台降模排序%'
      )
ORDER BY create_time;
