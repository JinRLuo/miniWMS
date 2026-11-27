#!/usr/bin/python3 -I
"""Receive opaque Mini WMS backups through a restricted SSH key."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tarfile
import tempfile


ROOT = Path('/opt/miniwms/backups/aliapp-daily')
NAMES = {'database.sql.gz', 'runtime-config.tar.gz'}
MAX_BYTES = 64 * 1024 * 1024


def main():
    os.umask(0o077)
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    ROOT.chmod(0o700)
    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
    day = now.strftime('%Y%m%d')
    if len(list(ROOT.glob(day + '-*'))) >= 3:
        raise ValueError('Daily backup upload limit reached')
    with tempfile.TemporaryDirectory(prefix='.incoming-', dir=ROOT) as temporary:
        stage = Path(temporary) / 'backup'
        stage.mkdir(mode=0o700)
        files = {}
        total = 0
        with tarfile.open(fileobj=sys.stdin.buffer, mode='r|') as archive:
            for member in archive:
                total += member.size
                if (not member.isfile() or member.size <= 0 or member.name not in NAMES
                        or member.name in files or total > MAX_BYTES):
                    raise ValueError('Unexpected backup file or size')
                checksum = hashlib.sha256()
                with archive.extractfile(member) as source, (stage / member.name).open('xb') as output:
                    while True:
                        chunk = source.read(1024 * 1024)
                        if not chunk:
                            break
                        output.write(chunk)
                        checksum.update(chunk)
                files[member.name] = {'bytes': member.size, 'sha256': checksum.hexdigest()}
        if set(files) != NAMES or any(item['bytes'] == 0 for item in files.values()):
            raise ValueError('Backup files missing or empty')
        (stage / 'receipt.json').write_text(json.dumps(files, indent=2, sort_keys=True))
        target = ROOT / now.strftime('%Y%m%d-%H%M%S-%f')
        stage.rename(target)
    cutoff = now.date() - datetime.timedelta(days=31)
    for path in ROOT.iterdir():
        if (path.is_dir() and not path.is_symlink()
                and re.fullmatch(r'\d{8}-\d{6}-\d{6}', path.name)
                and datetime.datetime.strptime(path.name[:8], '%Y%m%d').date() < cutoff):
            shutil.rmtree(path)
    print('Mini WMS offsite backup received: ' + str(target), flush=True)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, tarfile.TarError) as error:
        print('Backup rejected: ' + str(error), file=sys.stderr)
        sys.exit(1)
