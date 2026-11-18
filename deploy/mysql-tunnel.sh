#!/usr/bin/env bash
# Run locally and keep this process open while the application uses MySQL.
set -euo pipefail

host=${1:-zoomzoom}
port=${2:-13306}
ip=$(ssh -T -o BatchMode=yes -o ConnectTimeout=12 "$host"   'docker inspect --format "{{(index .NetworkSettings.Networks \"muse-pro_default\").IPAddress}}" musepro-mysql')
if [[ ! "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  printf '%s\n' 'Could not resolve the existing MySQL container bridge address.' >&2
  exit 1
fi
printf 'Opening MySQL tunnel on 127.0.0.1:%s\n' "$port"
exec ssh -NT -o BatchMode=yes -o ConnectTimeout=12 -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -L "127.0.0.1:$port:$ip:3306" "$host"
