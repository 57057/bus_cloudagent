"""Restore a chosen backup into separate, uniquely prefixed tables and verify its manifest.

No existing table is overwritten or dropped. The verification tables are kept
for review; use the same approach when rehearsing an application rollback.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid
import re
from backup_business import ROOT
sys.path.insert(0, str(ROOT))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup',type=Path)
    args=parser.parse_args()
    folder=args.backup.resolve()
    manifest=json.loads((folder/'manifest.json').read_text())
    dump=folder/'business.sql'
    assert hashlib.sha256(dump.read_bytes()).hexdigest()==manifest['sha256'], 'Backup checksum mismatch'
    if manifest.get('application_key_sha256'):
        from dotenv import dotenv_values
        key_setting=os.getenv('APPLICATION_KEY_FILE') or dotenv_values(ROOT/'business-java/.env').get('APPLICATION_KEY_FILE') or 'runtime/application-secret.key'
        key_path=Path(key_setting)
        if not key_path.is_absolute():key_path=ROOT/key_path
        assert key_path.exists() and hashlib.sha256(key_path.read_bytes()).hexdigest()==manifest['application_key_sha256'], 'Restore requires the separately protected original application encryption key'
    from agent.config import get_settings
    import pymysql
    s=get_settings()
    name=s.mysql_database
    prefix='restore_'+uuid.uuid4().hex[:12]+'_'
    names={table:prefix+table for table in manifest['tables']}
    connection=pymysql.connect(host=s.mysql_host,port=s.mysql_port,user=s.mysql_user,password=s.mysql_password,charset='utf8mb4')
    with connection,connection.cursor() as cur:
        for new_name in names.values():
            cur.execute('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s AND table_name=%s',(name,new_name))
            assert cur.fetchone()[0]==0, 'Verification target already exists'
    def quote(v):return '"'+str(v).replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r')+'"'
    fd,secret=tempfile.mkstemp(prefix='restore-',suffix='.cnf',dir=ROOT/'runtime')
    try:
        with os.fdopen(fd,'w',encoding='utf-8') as f:
            f.write('[client]\n'+'\n'.join(k+'='+quote(v) for k,v in {'host':s.mysql_host,'port':s.mysql_port,'user':s.mysql_user,'password':s.mysql_password}.items()))
        sql=dump.read_text(encoding='utf-8')
        for table,new_name in names.items():
            pattern=r'(?m)^((?:(?:DROP TABLE IF EXISTS|CREATE TABLE|LOCK TABLES|INSERT INTO) |/\*!\d+ ALTER TABLE ))`'+re.escape(table)+r'`'
            sql=re.sub(pattern,lambda m:m.group(1)+'`'+new_name+'`',sql)
        subprocess.run(['D:/mysql-5.7.24-winx64/bin/mysql.exe','--defaults-extra-file='+secret,'--default-character-set=utf8mb4',name],input=sql.encode('utf-8'),check=True)
    finally:Path(secret).unlink(missing_ok=True)
    c=pymysql.connect(host=s.mysql_host,port=s.mysql_port,user=s.mysql_user,password=s.mysql_password,database=name)
    with c,c.cursor() as cur:
        for table,count in manifest['tables'].items():
            assert table.replace('_','').isalnum()
            cur.execute('SELECT COUNT(*) FROM `'+names[table]+'`');assert cur.fetchone()[0]==count,table
        if 'cloud_orders' in manifest['tables']:
            cur.execute('SELECT COALESCE(SUM(ROUND(amount*100)),0) FROM `'+names['cloud_orders']+'`');assert int(cur.fetchone()[0])==manifest['historical_cents']
    result={'backup':str(folder),'restored_database':name,'verification_tables':names,'verified_tables':manifest['tables'],'historical_cents':manifest['historical_cents']}
    (ROOT/'runtime/backup-verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print('Restore verified using separate prefixed tables in:',name)
    print('Verification prefix:',prefix)
    print('Table counts and historical amount match manifest.')


if __name__=='__main__':main()
