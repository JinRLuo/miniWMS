#!/usr/bin/env python3
"""Compare restored databases without printing business rows or credentials."""
import argparse
import hashlib
import json
import re
import subprocess


MYSQL = """
if [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ]; then
    MYSQL_PWD=$(cat "$MYSQL_ROOT_PASSWORD_FILE")
else
    MYSQL_PWD=${MYSQL_ROOT_PASSWORD:-}
fi
export MYSQL_PWD
exec mysql --protocol=socket --user=root --default-character-set=utf8mb4 \
    --batch --raw --skip-column-names
"""


def identifier(value):
    if not re.fullmatch(r"[A-Za-z0-9_]+", value):
        raise ValueError("Unexpected SQL identifier")
    return "`" + value + "`"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="miniwms-mysql-1")
    parser.add_argument("--database", default="miniwms")
    args = parser.parse_args()
    database = identifier(args.database)

    def query(sql):
        result = subprocess.run(
            ["docker", "exec", "-i", args.container, "sh", "-eu", "-c", MYSQL],
            input=("SET time_zone='+00:00';\n" + sql + ";\n").encode(),
            capture_output=True, check=True, timeout=60,
        )
        return result.stdout

    tables = query(
        "SELECT TABLE_NAME FROM information_schema.TABLES "
        f"WHERE TABLE_SCHEMA='{args.database}' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"
    ).decode().splitlines()
    result = {}
    for name in tables:
        table = identifier(name)
        columns = query(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
            f"WHERE TABLE_SCHEMA='{args.database}' AND TABLE_NAME='{name}' ORDER BY ORDINAL_POSITION"
        ).decode().splitlines()
        keys = query(
            "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
            f"WHERE TABLE_SCHEMA='{args.database}' AND TABLE_NAME='{name}' "
            "AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION"
        ).decode().splitlines()
        if not keys:
            raise ValueError("Every fingerprinted table must have a primary key")
        projection = ",".join(identifier(column) for column in columns)
        order = ",".join(identifier(column) for column in keys)
        rows = query(f"SELECT JSON_ARRAY({projection}) FROM {database}.{table} ORDER BY {order}")
        count = int(query(f"SELECT COUNT(*) FROM {database}.{table}"))
        schema = query(f"SHOW CREATE TABLE {database}.{table}").decode()
        # Auto-increment next values are checked separately from schema identity.
        schema = re.sub(r" AUTO_INCREMENT=\d+", "", schema)
        result[name] = {
            "rows": count,
            "sha256": hashlib.sha256(rows).hexdigest(),
            "schemaSha256": hashlib.sha256(schema.encode()).hexdigest(),
            "nextAutoIncrement": int(query(
                "SELECT COALESCE(AUTO_INCREMENT,0) FROM information_schema.TABLES "
                f"WHERE TABLE_SCHEMA='{args.database}' AND TABLE_NAME='{name}'"
            )),
        }
    totals = query(
        "SELECT JSON_ARRAY(h.warehouse_id,CAST(SUM(i.quantity) AS CHAR),COUNT(*)) "
        f"FROM {database}.inventory i JOIN {database}.shelf_slots s ON s.id=i.slot_id "
        f"JOIN {database}.shelves h ON h.id=s.shelf_id "
        "GROUP BY h.warehouse_id ORDER BY h.warehouse_id"
    ).decode().splitlines()
    print(json.dumps({"database": args.database, "tables": result,
                      "inventoryTotals": [json.loads(line) for line in totals]}, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
