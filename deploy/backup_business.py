"""Consistent MySQL backup before P0 migration; never print credentials.

The restore command is deliberately not automatic: restore into a separate database
first, verify the manifest, then change MYSQL_DATABASE for an application rollback.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import re

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def main():
    from agent.config import get_settings
    import pymysql
    s = get_settings()
    folder = ROOT / 'runtime/backups' / datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    folder.mkdir(parents=True, exist_ok=True)
    c = pymysql.connect(host=s.mysql_host, port=s.mysql_port, user=s.mysql_user,
                        password=s.mysql_password, database=s.mysql_database)
    manifest = {'database': s.mysql_database, 'tables': {}, 'historical_cents': 0}
    with c, c.cursor() as cur:
        cur.execute('SHOW TABLES')
        for (table,) in cur.fetchall():
            if re.match(r'^restore_[0-9a-f]{12}_',table):
                continue  # verify_backup专用副本不是业务表，避免递归备份演练数据。
            if not table.replace('_', '').isalnum():
                raise ValueError('Unexpected table name')
            cur.execute('SELECT COUNT(*) FROM `' + table + '`')
            manifest['tables'][table] = cur.fetchone()[0]
        if 'cloud_orders' in manifest['tables']:
            cur.execute('SELECT COALESCE(SUM(ROUND(amount*100)),0) FROM cloud_orders')
            manifest['historical_cents'] = int(cur.fetchone()[0])
    # MySQL's option file quoting escapes backslashes, quotes and newlines.
    def quote(value):
        return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'
    fd, name = tempfile.mkstemp(prefix='mysql-backup-', suffix='.cnf', dir=ROOT / 'runtime')
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            f.write('[client]\n' + '\n'.join(k + '=' + quote(v) for k, v in
                {'host':s.mysql_host,'port':s.mysql_port,'user':s.mysql_user,'password':s.mysql_password}.items()))
        dump = folder / 'business.sql'
        with dump.open('wb') as output:
            subprocess.run(['D:/mysql-5.7.24-winx64/bin/mysqldump.exe', '--defaults-extra-file=' + name,
                '--single-transaction', '--hex-blob', '--default-character-set=utf8mb4', s.mysql_database,*manifest['tables']], stdout=output, check=True)
        manifest['sha256'] = hashlib.sha256(dump.read_bytes()).hexdigest()
        from dotenv import dotenv_values
        key_setting=os.getenv('APPLICATION_KEY_FILE') or dotenv_values(ROOT/'business-java/.env').get('APPLICATION_KEY_FILE') or 'runtime/application-secret.key'
        key_path=Path(key_setting)
        if not key_path.is_absolute():key_path=ROOT/key_path
        if key_path.exists():
            # 主密钥不随SQL复制，仅保存指纹。恢复前必须另行提供受控备份的原密钥。
            manifest['application_key_sha256']=hashlib.sha256(key_path.read_bytes()).hexdigest()
        (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        print('Backup:', folder)
    finally:
        Path(name).unlink(missing_ok=True)


if __name__ == '__main__':
    main()
