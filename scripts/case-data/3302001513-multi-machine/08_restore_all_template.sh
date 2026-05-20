#!/bin/zsh
set -euo pipefail

BACKUP_DIR=${1:?请传入备份目录}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3307}
DB_USER=${DB_USER:-root}
DB_PASSWORD=${DB_PASSWORD:-123456}
DB_NAME=${DB_NAME:-apslh}
SCHEDULE_DATE=${2:-2026-05-03}

if [[ ! -d "${BACKUP_DIR}" ]]; then
  echo "备份目录不存在: ${BACKUP_DIR}" >&2
  exit 1
fi

if [[ -f "${BACKUP_DIR}/restore_source_data.sql" ]]; then
  RESTORE_SOURCE_SQL="${BACKUP_DIR}/restore_source_data.sql"
elif [[ -f "${BACKUP_DIR}/restore_source.sql" ]]; then
  RESTORE_SOURCE_SQL="${BACKUP_DIR}/restore_source.sql"
else
  echo "未找到源数据回滚文件: ${BACKUP_DIR}/restore_source_data.sql 或 ${BACKUP_DIR}/restore_source.sql" >&2
  exit 1
fi

mysql_base=(mysql -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USER}" -p"${DB_PASSWORD}" "${DB_NAME}")

for required_file in \
  "${BACKUP_DIR}/t_lh_schedule_result_${SCHEDULE_DATE}.sql" \
  "${BACKUP_DIR}/t_lh_unscheduled_result_${SCHEDULE_DATE}.sql" \
  "${BACKUP_DIR}/t_lh_mould_change_plan_${SCHEDULE_DATE}.sql"
do
  if [[ ! -f "${required_file}" ]]; then
    echo "缺少回滚文件: ${required_file}" >&2
    exit 1
  fi
done

"${mysql_base[@]}" < "${RESTORE_SOURCE_SQL}"
"${mysql_base[@]}" -e "DELETE FROM t_lh_schedule_result WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}';"
"${mysql_base[@]}" -e "DELETE FROM t_lh_unscheduled_result WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}';"
"${mysql_base[@]}" -e "DELETE FROM t_lh_mould_change_plan WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}';"
"${mysql_base[@]}" < "${BACKUP_DIR}/t_lh_schedule_result_${SCHEDULE_DATE}.sql"
"${mysql_base[@]}" < "${BACKUP_DIR}/t_lh_unscheduled_result_${SCHEDULE_DATE}.sql"
"${mysql_base[@]}" < "${BACKUP_DIR}/t_lh_mould_change_plan_${SCHEDULE_DATE}.sql"
