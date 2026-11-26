#!/usr/bin/env python3
"""Install Mini WMS Redis without restarting the running application.

Run as root on the production host, next to compose.runtime.yaml.
"""
import datetime
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time


ROOT = Path("/opt/miniwms")


def main():
    if os.geteuid() != 0:
        raise SystemExit("Run this installer as root on the Mini WMS server")
    template = Path(__file__).with_name("compose.runtime.yaml").read_text()
    with (ROOT / ".deploy.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        env_file = ROOT / ".app.env"
        config = env_file.read_text()
        settings = dict(line.split("=", 1) for line in config.splitlines() if "=" in line and not line.startswith("#"))
        redis_dir = ROOT / "redis"
        redis_dir.mkdir(mode=0o700, exist_ok=True)
        redis_config = redis_dir / "redis.conf"
        password = settings.get("WMS_REDIS_PASSWORD")
        if redis_config.exists():
            current_password = re.search(r"^requirepass ([a-f0-9]+)$", redis_config.read_text(), re.M)
            if not current_password or password != current_password.group(1):
                raise RuntimeError("Existing Redis credentials differ; refusing to rotate them automatically")
        password = password or secrets.token_hex(32)
        if not re.fullmatch(r"[a-f0-9]{64}", password):
            raise RuntimeError("Unexpected Redis credential format")

        backup = ROOT / "backups" / ("before-redis-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
        backup.mkdir(parents=True, mode=0o700)
        for path in (env_file, ROOT / "compose.runtime.yaml", redis_config):
            if path.exists():
                target = backup / path.name
                shutil.copy2(path, target)
                target.chmod(0o600)

        updates = {
            "SPRING_PROFILES_ACTIVE": "redis",
            "WMS_REDIS_HOST": "redis",
            "WMS_REDIS_PORT": "6379",
            "WMS_REDIS_PASSWORD": password,
            "WMS_REDIS_DATABASE": "0",
        }
        profiles = settings.get("SPRING_PROFILES_ACTIVE", "").strip('"\'')
        updates["SPRING_PROFILES_ACTIVE"] = ",".join(dict.fromkeys([p for p in profiles.split(",") if p] + ["redis"]))
        lines = [line for line in config.splitlines() if line.split("=", 1)[0] not in updates]
        env_file.write_text("\n".join(lines + [key + "=" + value for key, value in updates.items()]) + "\n")
        env_file.chmod(0o600)
        redis_config.write_text("\n".join([
            "bind 0.0.0.0", "port 6379", "protected-mode yes", "requirepass " + password,
            "dir /data", "appendonly yes", "appendfsync everysec", 'save ""',
            "maxmemory 128mb", "maxmemory-policy noeviction", "maxclients 256", "tcp-keepalive 60",
        ]) + "\n")
        # The enclosing directory is root-only; the container's redis user reads the bind-mounted file.
        redis_config.chmod(0o644)
        (ROOT / "compose.runtime.yaml").write_text(template)
        env = {**os.environ, "WMS_REVISION": (ROOT / "current").resolve().name}
        subprocess.run(["docker", "compose", "-f", "compose.runtime.yaml", "config", "--quiet"], cwd=ROOT, env=env, check=True)
        subprocess.run(["docker", "compose", "-f", "compose.runtime.yaml", "up", "-d", "--no-deps", "redis"], cwd=ROOT, env=env, check=True)
        for _ in range(30):
            info = json.loads(subprocess.check_output(["docker", "inspect", "miniwms-redis-1"], text=True))[0]
            if info["State"].get("Health", {}).get("Status") == "healthy":
                assert not info["HostConfig"]["PortBindings"], "Redis must not publish host ports"
                print(json.dumps({"redis": "healthy", "publishedPorts": [], "persistence": "AOF", "backup": str(backup), "applicationRestarted": False}))
                return
            time.sleep(2)
        raise RuntimeError("Redis did not become healthy; the application has not been restarted")


if __name__ == "__main__":
    main()
