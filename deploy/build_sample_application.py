"""Build approved sample images in the existing project VM; never prune other images/data."""
from pathlib import Path
import os,shutil,subprocess,sys,tarfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from deploy.vm_command import connect

def main():
    source=ROOT/'deployment-runner';work=ROOT/'runtime/application-images';work.mkdir(exist_ok=True)
    env=dict(os.environ,JAVA_HOME='D:/java');env['PATH']='D:/java/bin;'+env.get('PATH','')
    subprocess.run([str(ROOT/'runtime/tools/apache-maven-3.9.11/bin/mvn.cmd'),'-B','-ntp',
        '-Dmaven.repo.local='+str(ROOT/'runtime/maven-repository'),'-f',str(source/'demo-backend/pom.xml'),'package'],env=env,check=True)
    for name in ('backend','frontend'):
        folder=work/name;folder.mkdir(exist_ok=True)
        shutil.copy2(source/('demo-'+name)/'Dockerfile',folder/'Dockerfile')
    shutil.copy2(source/'demo-backend/target/sample-app-1.0.0.jar',work/'backend/app.jar')
    (work/'backend/health').mkdir(exist_ok=True)
    subprocess.run(['D:/java/bin/javac.exe','-d',str(work/'backend/health'),str(source/'demo-backend/Health.java')],check=True)
    front=ROOT/'front/platform-web'
    subprocess.run(['node',str(front/'node_modules/esbuild/bin/esbuild'),str(source/'demo-frontend/main.tsx'),
        '--bundle','--minify','--outfile='+str(work/'frontend/app.js'),
        '--alias:react='+str(front/'node_modules/react/index.js'),
        '--alias:react-dom/client='+str(front/'node_modules/react-dom/client.js')],check=True)
    for file in ('index.html','nginx.conf'):shutil.copy2(source/'demo-frontend'/file,work/'frontend'/file)
    archive=work/'images.tar'
    with tarfile.open(archive,'w') as t:
        for name in ('backend','frontend'):t.add(work/name,arcname=name)
    with connect() as client:
        _,out,err=client.exec_command('mkdir -p /home/cloudagent/application-images');assert out.channel.recv_exit_status()==0
        with client.open_sftp() as sftp:sftp.put(str(archive),'/home/cloudagent/application-images/images.tar')
        command='cd /home/cloudagent/application-images && tar xf images.tar && docker build -t cloudagent-demo-backend:1 backend && docker build -t cloudagent-demo-frontend:1 frontend'
        _,out,err=client.exec_command(command,timeout=900)
        for line in out:print(line,end='',flush=True)
        status=out.channel.recv_exit_status()
        if status:print(err.read().decode());raise SystemExit(status)

if __name__=='__main__':main()
