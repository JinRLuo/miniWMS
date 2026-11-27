#!/usr/bin/env bash
# Run locally and keep this process open while the application uses MySQL.
set -euo pipefail

host=${1:-aliapp}
port=${2:-13306}
container=${3:-miniwms-mysql-1}
if [[ ! "$container" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
  printf '%s\n' 'Invalid MySQL container name.' >&2
  exit 2
fi
ip=$(ssh -T -o BatchMode=yes -o ConnectTimeout=12 "$host" \
  "docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' '$container'")
if [[ ! "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  printf '%s\n' 'Could not resolve the existing MySQL container bridge address.' >&2
  exit 1
fi
printf 'Opening MySQL tunnel on 127.0.0.1:%s\n' "$port"
exec ssh -NT -o BatchMode=yes -o ConnectTimeout=12 -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -L "127.0.0.1:$port:$ip:3306" "$host"
