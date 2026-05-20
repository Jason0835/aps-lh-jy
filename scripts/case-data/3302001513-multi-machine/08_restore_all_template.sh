#!/bin/zsh
set -euo pipefail

BACKUP_DIR=${1:?请传入备份目录}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3307}
DB_USER=${DB_USER:-root}
DB_PASSWORD=${DB_PASSWORD:-123456}
DB_NAME=${DB_NAME:-apslh}
SCHEDULE_DATE=${2:-2026-05-03}

mysql_base=(mysql -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USER}" -p"${DB_PASSWORD}" "${DB_NAME}")

"${mysql_base[@]}" < "${BACKUP_DIR}/restore_source_data.sql"
"${mysql_base[@]}" -e "DELETE FROM t_lh_schedule_result WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}';"
"${mysql_base[@]}" -e "DELETE FROM t_lh_unscheduled_result WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}';"
"${mysql_base[@]}" -e "DELETE FROM t_lh_mould_change_plan WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}';"
"${mysql_base[@]}" < "${BACKUP_DIR}/t_lh_schedule_result_${SCHEDULE_DATE}.sql"
"${mysql_base[@]}" < "${BACKUP_DIR}/t_lh_unscheduled_result_${SCHEDULE_DATE}.sql"
"${mysql_base[@]}" < "${BACKUP_DIR}/t_lh_mould_change_plan_${SCHEDULE_DATE}.sql"
