"""Install same-host MySQL 8.4 from the official Ubuntu repositories for the 2GB demo.

No public bind, no root password reset, no local database migration. The application
password is read only on the target host and sent to mysql through stdin.
"""
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from deploy.ecs_linux_ssh import connect
from deploy.deploy_ecs_lite import run

ROOT=Path(__file__).resolve().parents[1]
CONFIG='''[mysqld]
bind-address=172.17.0.1
mysqlx=0
innodb_buffer_pool_size=64M
innodb_log_buffer_size=8M
innodb_redo_log_capacity=64M
max_connections=20
performance_schema=OFF
table_open_cache=128
'''
BOOTSTRAP='''import re,subprocess,time
from pathlib import Path
values=dict(line.split('=',1) for line in Path('/opt/cloudagent/current/.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
password=values['MYSQL_PASSWORD']
assert re.fullmatch('[a-f0-9]{64}',password)
for _ in range(40):
    result=subprocess.run(['mysqladmin','--protocol=socket','-uroot','ping','--silent'],capture_output=True)
    if result.returncode==0:break
    time.sleep(2)
else:raise RuntimeError('Native MySQL not ready')
sql="CREATE DATABASE IF NOT EXISTS cloud_platform CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'cloudagent'@'172.%' IDENTIFIED BY '"+password+"'; GRANT ALL PRIVILEGES ON cloud_platform.* TO 'cloudagent'@'172.%'; SELECT 1;"
result=subprocess.run(['mysql','--protocol=socket','-uroot'],input=sql,text=True,capture_output=True)
if result.returncode:raise RuntimeError('Database account bootstrap failed; inspect privately on host')
print('Native MySQL application database and private Docker-network account ready')
'''


def main():
    state=json.loads((ROOT/'runtime/ecs-linux/deployment.json').read_text())
    remote=state['remote']
    localenv=ROOT/'runtime/ecs-linux/cloud.env'
    if 'COMPOSE_FILE=' not in localenv.read_text():
        with localenv.open('a') as f:f.write('COMPOSE_FILE=compose.yaml:compose.native.yaml\n')
    with connect() as client:
        run(client,'install -d /etc/mysql/mysql.conf.d /etc/systemd/system/mysql.service.d; ln -sfn '+remote+' /opt/cloudagent/current')
        with client.open_sftp() as sftp:
            with sftp.file('/etc/mysql/mysql.conf.d/zz-cloudagent.cnf','w') as f:f.write(CONFIG)
            sftp.put(str(ROOT/'deploy/cloud/compose.native.yaml'),remote+'/compose.native.yaml')
            sftp.put(str(localenv),remote+'/.env');sftp.chmod(remote+'/.env',0o600)
            with sftp.file('/opt/cloudagent/private/bootstrap-mysql.py','w') as f:f.write(BOOTSTRAP)
            sftp.chmod('/opt/cloudagent/private/bootstrap-mysql.py',0o600)
            with sftp.file('/etc/systemd/system/mysql.service.d/cloudagent.conf','w') as f:
                f.write('[Unit]\nAfter=docker.service\nRequires=docker.service\n[Service]\nMemoryMax=384M\nMemorySwapMax=384M\nRestart=on-failure\nRestartSec=10\n')
        run(client,'export DEBIAN_FRONTEND=noninteractive; if apt-get install -y --no-install-recommends mysql-server > /var/log/cloudagent-mysql-install.log 2>&1; then systemctl daemon-reload; systemctl enable --now mysql; python3 /opt/cloudagent/private/bootstrap-mysql.py; mysql --protocol=socket -uroot -Nse "SELECT VERSION()"; else tail -n 15 /var/log/cloudagent-mysql-install.log; exit 1; fi',timeout=400)
        run(client,'cd '+remote+' && docker compose config --quiet; ss -lnt | grep 3306; systemctl is-active mysql',timeout=30)


if __name__=='__main__':main()
