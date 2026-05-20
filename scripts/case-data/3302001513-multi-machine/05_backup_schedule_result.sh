#!/bin/zsh
set -euo pipefail

DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3307}
DB_USER=${DB_USER:-root}
DB_PASSWORD=${DB_PASSWORD:-123456}
DB_NAME=${DB_NAME:-apslh}
SCHEDULE_DATE=${1:-2026-05-03}
BACKUP_DIR=${2:-/tmp/aps-lh-case-3302001513-$(date +%Y%m%d%H%M%S)}

mkdir -p "$BACKUP_DIR"

mysqldump_base=(mysqldump -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USER}" -p"${DB_PASSWORD}" --no-create-info "${DB_NAME}")

"${mysqldump_base[@]}" t_lh_schedule_result --where="FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}'" \
    > "${BACKUP_DIR}/t_lh_schedule_result_${SCHEDULE_DATE}.sql"
"${mysqldump_base[@]}" t_lh_unscheduled_result --where="FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}'" \
    > "${BACKUP_DIR}/t_lh_unscheduled_result_${SCHEDULE_DATE}.sql"
"${mysqldump_base[@]}" t_lh_mould_change_plan --where="FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}'" \
    > "${BACKUP_DIR}/t_lh_mould_change_plan_${SCHEDULE_DATE}.sql"

cat > "${BACKUP_DIR}/restore_all.sh" <<EOF
#!/bin/zsh
set -euo pipefail
backup_dir=\$(cd "\$(dirname "\$0")" && pwd)
mysql_base=(mysql -h${DB_HOST} -P${DB_PORT} -u${DB_USER} -p${DB_PASSWORD} ${DB_NAME})
"\${mysql_base[@]}" < "\$backup_dir/restore_source_data.sql"
"\${mysql_base[@]}" -e "DELETE FROM t_lh_schedule_result WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}'; DELETE FROM t_lh_unscheduled_result WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}'; DELETE FROM t_lh_mould_change_plan WHERE FACTORY_CODE='116' AND SCHEDULE_DATE='${SCHEDULE_DATE}';"
"\${mysql_base[@]}" < "\$backup_dir/t_lh_schedule_result_${SCHEDULE_DATE}.sql"
"\${mysql_base[@]}" < "\$backup_dir/t_lh_unscheduled_result_${SCHEDULE_DATE}.sql"
"\${mysql_base[@]}" < "\$backup_dir/t_lh_mould_change_plan_${SCHEDULE_DATE}.sql"
EOF

chmod +x "${BACKUP_DIR}/restore_all.sh"
echo "结果备份完成: ${BACKUP_DIR}"
