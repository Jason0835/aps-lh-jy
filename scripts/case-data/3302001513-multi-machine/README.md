# 3302001513 续作多机台案例脚本

这组脚本用于复现 `3302001513` 续作同 SKU 多机台案例，并保留备份、核对、回滚、结果导出能力。

## 脚本说明

- `01_backup_current_data.sh`
  - 备份当前源数据，生成 `restore_source_data.sql` 和 `precheck.txt`
- `02_setup_multi_machine_case.sql`
  - 造续作同 SKU 多机台案例源数据
- `03_update_sku_lh_capacity.sql`
  - 把 `3302001513` 单班产能改成 `16`
- `04_verify_case_data.sql`
  - 核对机台主数据、机台在线、月计划、SKU 日硫化产能
- `05_backup_schedule_result.sh`
  - 备份指定排程日的结果表、未排表、换模计划表，并生成 `restore_all.sh`
- `06_query_latest_batch_result.sql`
  - 查询最新批次、重点物料结果和过程日志
- `07_restore_source_data.sql`
  - 恢复本次案例修改前的源数据
- `08_restore_all_template.sh`
  - 按备份目录恢复源数据和结果表模板

## 推荐执行顺序

```bash
cd /Users/Jason/IdeaProjects/test/aps-lh-parent/scripts/case-data/3302001513-multi-machine
./01_backup_current_data.sh /tmp/aps-lh-case-3302001513-$(date +%Y%m%d%H%M%S)
mysql -h127.0.0.1 -P3307 -uroot -p123456 apslh < 02_setup_multi_machine_case.sql
mysql -h127.0.0.1 -P3307 -uroot -p123456 apslh < 03_update_sku_lh_capacity.sql
mysql -h127.0.0.1 -P3307 -uroot -p123456 apslh < 04_verify_case_data.sql
```

执行真实排程前，如果需要保留当日结果表快照，再执行：

```bash
./05_backup_schedule_result.sh 2026-05-03 /tmp/aps-lh-case-3302001513-$(date +%Y%m%d%H%M%S)
```

## 恢复方式

只恢复源数据：

```bash
mysql -h127.0.0.1 -P3307 -uroot -p123456 apslh < 07_restore_source_data.sql
```

如果某次备份目录里已经导出了结果表，可以进入对应备份目录执行：

```bash
./restore_all.sh
```
