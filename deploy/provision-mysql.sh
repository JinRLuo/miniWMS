#!/usr/bin/env bash
# Intended for the Docker host. Supply the new application password on stdin.
set -euo pipefail

container=${1:?Pass the existing MySQL container name}
IFS= read -r password
if [[ ! "$password" =~ ^[0-9a-f]{64}$ ]]; then
  printf '%s\n' 'Expected a 64-character random hexadecimal application password.' >&2
  exit 2
fi

printf '%s\n' \
  'CREATE DATABASE IF NOT EXISTS miniwms CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' \
  "CREATE USER IF NOT EXISTS 'miniwms_app'@'%' IDENTIFIED BY '$password';" \
  "GRANT ALL PRIVILEGES ON miniwms.* TO 'miniwms_app'@'%';" |
  docker exec -i "$container" sh -eu -c '
    if [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ]; then
      MYSQL_PWD=$(cat "$MYSQL_ROOT_PASSWORD_FILE")
    else
      MYSQL_PWD=${MYSQL_ROOT_PASSWORD:-}
    fi
    export MYSQL_PWD
    exec mysql --protocol=socket --user=root --batch
  '

printf '%s\n' "$password" |
  docker exec -i "$container" sh -eu -c '
    IFS= read -r MYSQL_PWD
    export MYSQL_PWD
    exec mysql --protocol=tcp --host=127.0.0.1 --user=miniwms_app --database=miniwms --batch \
      --execute="SELECT VERSION() AS version, DATABASE() AS schema_name, CURRENT_USER() AS app_account; SHOW GRANTS;"
  '
