import pymysql
from pymysql.err import OperationalError, ProgrammingError

# ====================== 【必须修改】数据库配置（和导出脚本一致） ======================
DB_CONFIG = {
    "host": "127.0.0.1",       # 数据库地址  16.162.95.221
    "port": 3307,              # 端口  3306
    "user": "root",            # 用户名 apslh
    "password": "123456",     # 密码 747452
    "database": "apslh",     # 数据库名 apslh
    "charset": "utf8mb4"       # 字符集 utf8mb4
}

# ====================== 配置项 ======================
SQL_FILE_PATH = "table_structure_data.sql"  # SQL文件路径
BATCH_COMMIT_SIZE = 100  # 批量提交大小

# ====================== 专业SQL解析+执行（终极修复） ======================
def execute_sql_file():
    conn = None
    cursor = None
    try:
        # 连接数据库，关闭自动提交
        conn = pymysql.connect(**DB_CONFIG, autocommit=False)
        cursor = conn.cursor()
        print("✅ 数据库连接成功")

        # 1. 逐行读取SQL文件，正确解析完整SQL语句（核心修复）
        with open(SQL_FILE_PATH, "r", encoding="utf-8") as f:
            sql_lines = f.readlines()

        full_sql = ""
        sql_statements = []
        for line in sql_lines:
            line_strip = line.strip()
            # 跳过注释、空行
            if not line_strip or line_strip.startswith("--") or line_strip.startswith("/*"):
                continue
            full_sql += line
            # 遇到分号，代表一条完整SQL结束
            if line_strip.endswith(";"):
                sql_statements.append(full_sql.strip())
                full_sql = ""

        total = len(sql_statements)
        print(f"📊 解析完成，共 {total} 条完整SQL语句，开始执行...")

        # 2. 批量执行
        success_count = 0
        for idx, sql in enumerate(sql_statements, 1):
            try:
                cursor.execute(sql)
                success_count += 1
            except Exception as e:
                err = str(e)
                # 忽略表已存在/不存在的常规错误
                if "1050" in err or "1051" in err:
                    print(f"⚠️  跳过：表已存在/不存在")
                    continue
                # 打印错误详情
                print(f"\n❌ 第{idx}条SQL执行失败")
                print(f"错误：{err}")
                conn.rollback()
                return

            # 批量提交事务
            if idx % BATCH_COMMIT_SIZE == 0:
                conn.commit()
                print(f"✅ 已执行 {idx}/{total} 条")

        # 最终提交
        conn.commit()
        print(f"\n🎉 执行成功！总计执行 {success_count}/{total} 条SQL")

    except FileNotFoundError:
        print(f"❌ 未找到文件：{SQL_FILE_PATH}")
    except OperationalError:
        print("❌ 数据库连接失败，请检查配置")
    except Exception as e:
        print(f"❌ 程序异常：{str(e)}")
        if conn:
            conn.rollback()
    finally:
        if cursor:
            cursor.close()
        if conn:
            conn.close()
        print("\n🔌 数据库连接已关闭")

if __name__ == "__main__":
    execute_sql_file()