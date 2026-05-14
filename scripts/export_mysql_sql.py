import pymysql
from pymysql.err import OperationalError, ProgrammingError

# ====================== 【必须修改】数据库配置 ======================

DB_CONFIG = {
    "host": "172.21.7.130",       # 数据库地址 192.168.2.124
    "port": 3306,              # 端口  3306
    "user": "tbr_aps",            # 用户名  root
    "password": "NahPDzeShSxA%",     # 密码 neW46Ik#Gq@
    "database": "jy_aps",     # 数据库名 jy_aps
    "charset": "utf8mb4"       # 字符集 utf8mb4
}



# ====================== 【必须修改】需要导出的表名列表 ======================
TABLE_NAMES = [
    "T_LH_MOULD_CLEAN_PLAN",
    "T_LH_MACHINE_INFO",
    "T_LH_SPECIAL_MATERIAL_BOM",
    "T_LH_MOULD_CHANGE_PLAN",
    "T_LH_PARAMS",
    "T_LH_SCHEDULE_PROCESS_LOG",
    "T_LH_SCHEDULE_RESULT",
    "T_LH_SHIFT_CONFIG",
    "T_LH_DAY_FINISH_QTY",
    "T_LH_SCHE_FINISH_QTY",
    "T_LH_SPECIFY_MACHINE",
    "T_LH_UNSCHEDULED_RESULT",
    "T_MDM_DEV_MAINTENANCE_PLAN",
    "T_MDM_DEVICE_PLAN_SHUT",
    "T_LH_REPAIR_CAPSULE",
    "T_MDM_MATERIAL_INFO",
    "T_MDM_MONTH_SURPLUS",
    "T_MDM_SKU_LH_CAPACITY",
    "T_MDM_SKU_MOULD_REL",
    "T_MDM_WORK_CALENDAR",
    "T_MP_MONTH_PLAN_PROD_FINAL",
    "T_MP_PROC_VERSION",
    "T_MDM_MOULD_INFO",
    "T_CX_STOCK",
    "T_MDM_MATERIAL_CONSUME_DETAIL",
    "T_MDM_SPECIAL_MATERIAL_RECORD",
    "T_LH_PRECISION_PLAN",
    "T_MP_ADJUST_RESULT",
    "T_MDM_SKU_CONSTRUCTION_REF",
    "T_MDM_CAPSULE_CHUCK",
    "T_LH_MACHINE_ONLINE_INFO"
]

# ====================== 导出文件配置 ======================
OUTPUT_SQL_FILE = "table_structure_data.sql"  # 输出的SQL文件名

# ====================== 核心导出函数 ======================
def export_mysql_table_sql():
    conn = None
    cursor = None
    try:
        # 1. 连接数据库
        conn = pymysql.connect(**DB_CONFIG)
        cursor = conn.cursor()
        print(f"✅ 数据库连接成功，开始导出表：{TABLE_NAMES}")

        # 打开文件写入SQL
        with open(OUTPUT_SQL_FILE, "w", encoding="utf-8") as f:
            # 写入文件头注释
            f.write("-- 自动生成：表结构 + 数据导出脚本\n")
            f.write(f"-- 数据库：{DB_CONFIG['database']}\n")
            f.write(f"-- 兼容MySQL5.6/5.7/8.0，自动修复排序规则\n")
            f.write("SET FOREIGN_KEY_CHECKS=0;\n\n")  # 关闭外键检查，避免导入报错

            # 遍历所有表
            for table in TABLE_NAMES:
                print(f"\n📦 正在处理表：{table}")

                # --------------------- 1. 生成建表语句 ---------------------
                try:
                    cursor.execute(f"SHOW CREATE TABLE `{table}`")
                    create_table_sql = cursor.fetchone()[1]  # 获取建表语句

                    # ====================== ✅ 核心修改：自动兼容低版本MySQL ======================
                    # 自动替换新版排序规则为全版本兼容格式
                    create_table_sql = create_table_sql.replace("utf8mb4_0900_ai_ci", "utf8mb4_general_ci")
                    # ==========================================================================

                    f.write(f"-- 建表语句：{table}\n")
                    f.write(f"DROP TABLE IF EXISTS `{table}`;\n")  # 先删除再创建
                    f.write(f"{create_table_sql};\n\n")
                except ProgrammingError:
                    print(f"❌ 表 {table} 不存在，跳过")
                    continue

                # --------------------- 2. 生成INSERT数据语句 ---------------------
                # 查询表所有数据
                cursor.execute(f"SELECT * FROM `{table}`")
                columns = [col[0] for col in cursor.description]  # 获取列名
                rows = cursor.fetchall()  # 获取所有数据

                if not rows:
                    f.write(f"-- {table} 表无数据\n\n")
                    print(f"✅ {table} 表无数据，仅导出结构")
                    continue

                # 拼接INSERT语句
                f.write(f"-- 数据插入语句：{table}\n")
                insert_prefix = f"INSERT INTO `{table}` ({', '.join([f'`{col}`' for col in columns])}) VALUES "
                f.write(insert_prefix)

                # 处理每一行数据
                value_lines = []
                for row in rows:
                    values = []
                    for val in row:
                        if val is None:
                            values.append("NULL")
                        elif isinstance(val, (int, float)):
                            values.append(str(val))
                        else:
                            # 字符串/日期：转义单引号，加引号包裹
                            val_str = str(val).replace("'", "''")
                            values.append(f"'{val_str}'")
                    value_lines.append(f"({', '.join(values)})")

                # 批量写入VALUES，用逗号分隔
                f.write(",\n".join(value_lines))
                f.write(";\n\n")
                print(f"✅ {table} 表导出完成，共 {len(rows)} 条数据")

            # 开启外键检查
            f.write("SET FOREIGN_KEY_CHECKS=1;\n")

        print(f"\n🎉 导出完成！兼容版SQL文件已保存至：{OUTPUT_SQL_FILE}")

    except OperationalError as e:
        print(f"❌ 数据库连接失败：{e}")
    except Exception as e:
        print(f"❌ 导出失败：{e}")
    finally:
        # 关闭连接
        if cursor:
            cursor.close()
        if conn:
            conn.close()
        print("\n🔌 数据库连接已关闭")

# ====================== 执行脚本 ======================
if __name__ == "__main__":
    export_mysql_table_sql()
