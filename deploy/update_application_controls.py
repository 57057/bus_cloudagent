"""Update only the authorized demo business service, Runner, and static portal.

No image registry access, cloud purchase, or whole-stack restart. Existing image,
database, Runner receipts and frontend are backed up before the bounded update.
"""
import hashlib
import json
from pathlib import Path
import sys
import time
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from deploy.ecs_linux_ssh import connect
from deploy.deploy_ecs_lite import run

def main():
    jar=ROOT/'business-java/target/business-1.0.0.jar'
    stamp=hashlib.sha256(jar.read_bytes()).hexdigest()[:12]
    stage='/opt/cloudagent/updates/application-controls-'+stamp
    with connect() as c:
        # Refuse to interrupt a task whose external result might still be unknown.
        _,out,_=c.exec_command("mysql -Nse \"SELECT COUNT(*) FROM cloud_platform.app_deployments WHERE state IN ('QUEUED','RUNNING','MANUAL_REQUIRED')\"")
        if out.read().decode().strip()!='0':raise RuntimeError('An application task is still active; finish or reconcile it before updating')
        run(c,'install -d -m 700 '+stage)
        with c.open_sftp() as s:
            s.put(str(jar),stage+'/business.jar')
            s.put(str(ROOT/'deployment-runner/runner.py'),stage+'/runner.py')
            with s.file(stage+'/Dockerfile','w') as f:f.write('FROM cloudagent-business:lite\nCOPY business.jar /opt/cloudagent/business.jar\n')
            # Future retries must not include private backups in the Docker build context.
            with s.file(stage+'/.dockerignore','w') as f:f.write('*\n!Dockerfile\n!business.jar\n')
        run(c,'cd '+stage+' && docker build --pull=false -t cloudagent-business:application-controls-'+stamp+' .',timeout=90)
        # Backup is kept private on the same host; it contains encrypted application values.
        run(c,'umask 077; mysqldump --single-transaction --routines --events cloud_platform > '+stage+'/before.sql && tar -czf '+stage+'/site-before.tgz -C /opt/cloudagent/current/site . && cp /opt/cloudagent-runner/runner.py '+stage+'/runner-before.py && docker inspect --format \'{{.Image}}\' cloudagent-lite-business-1 > '+stage+'/business-before-image.txt',timeout=60)
        run(c,'systemctl stop cloudagent-runner && cp /var/lib/cloudagent-runner/receipts.sqlite '+stage+'/receipts-before.sqlite && install -o root -g root -m 755 '+stage+'/runner.py /opt/cloudagent-runner/runner.py && docker tag cloudagent-business:lite cloudagent-business:before-controls-'+stamp+' && docker tag cloudagent-business:application-controls-'+stamp+' cloudagent-business:lite && cd /opt/cloudagent/current && docker compose up -d --no-deps --no-build --pull never business',timeout=60)
        healthy=False
        for _ in range(45):
            _,out,_=c.exec_command("curl -fsS --max-time 4 http://127.0.0.1:8080/health")
            if out.read().decode().strip():healthy=True;break
            time.sleep(2)
        if not healthy:raise RuntimeError('Business health failed; Runner remains stopped and rollback artifacts are retained at '+stage)
        with c.open_sftp() as s:
            # Upload immutable assets first and atomically switch HTML last.
            for local in (ROOT/'front/platform-web/dist/assets').iterdir():
                if local.is_file():s.put(str(local),'/opt/cloudagent/current/site/assets/'+local.name)
            s.put(str(ROOT/'front/platform-web/dist/index.html'),'/opt/cloudagent/current/site/index.html.next')
            s.posix_rename('/opt/cloudagent/current/site/index.html.next','/opt/cloudagent/current/site/index.html')
        run(c,'systemctl start cloudagent-runner && systemctl is-active cloudagent-runner video-two && mysql -Nse "SELECT version FROM cloud_platform.p0_schema_history ORDER BY version"',timeout=30)
    report={'stage':stage,'business_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'status':'UPDATED_HEALTHY','scope':'business, runner, static portal; other services retained'}
    (ROOT/'runtime/ecs-linux/application-controls-update.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report))

if __name__=='__main__':main()
