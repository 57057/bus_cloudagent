"""Platform-owned Linux executor. No model, user-supplied scripts, or arbitrary shell.

One Runner process per host, outbound HTTP(S), durable SQLite receipt journal.
Only managed containers bearing the exact allocation label can be stopped/removed.
"""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import threading
import urllib.error
import urllib.request


class DeploymentCancelled(Exception):
    """Explicit user cancellation before the server grants publication."""


class ExecutionInvalid(Exception):
    """Execution fencing no longer authorizes further publication."""


class Runner:
    def __init__(self, config, root):
        self.config = config
        # 每个线程复用自己的HTTP opener。重复创建默认HTTPSHandler会反复加载
        # TLS上下文，即使控制面地址是HTTP，也会产生不必要的内存与GC压力。
        self._http = threading.local()
        self._control = None
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db = sqlite3.connect(self.root / 'receipts.sqlite')
        self.db.execute('CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY, attempt INTEGER, state TEXT, result TEXT)')
        self.db.execute('CREATE TABLE IF NOT EXISTS releases(project TEXT PRIMARY KEY, task TEXT, allocation TEXT, backend TEXT, frontend TEXT, port INTEGER)')
        self.db.commit()

    def request(self, path, body):
        req = urllib.request.Request(self.config['base_url'] + '/internal/runner/' + path,
            data=json.dumps(body).encode(), headers={'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + self.config.get('credential', '')})
        # Disable ambient proxies: host control traffic must only go to the configured control plane.
        if not hasattr(self._http, 'opener'):
            self._http.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with self._http.opener.open(req, timeout=15) as response:
            return json.load(response)['data']

    def docker(self, *args, timeout=120):
        process = subprocess.Popen(['docker', *map(str,args)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.monotonic() + timeout
        while True:
            # Only interrupt read-only health probes. Mutating Docker requests must finish
            # before cleanup, otherwise the daemon could create a container after cancellation.
            if args[0] == 'exec' and self._control and not self._control['publishing']:
                if self._control['cancel'].is_set() or self._control['invalid'].is_set():
                    process.kill();process.communicate()
                    if self._control['cancel'].is_set():raise DeploymentCancelled()
                    raise ExecutionInvalid('Execution authorization changed')
            try:
                stdout, stderr = process.communicate(timeout=min(.2, max(.01, deadline-time.monotonic())))
                break
            except subprocess.TimeoutExpired:
                if time.monotonic() >= deadline:
                    process.kill();process.communicate()
                    raise RuntimeError('Container operation timed out: '+args[0]) from None
        if process.returncode:
            # Never put raw stderr/env/container logs into the control plane exception path.
            raise RuntimeError('Container operation failed: ' + args[0])
        return (stdout + (stderr if args[0]=='logs' else '')).strip()

    def register(self):
        import platform
        capabilities = {'architecture': platform.machine(), 'docker_ready': bool(self.docker('version','--format','{{.Server.Version}}')), 'execution_protocol': 2}
        pairs=self.config.setdefault('approved_pairs',[])
        if self.config.get('backend_digest') and self.config.get('frontend_digest'):
            previous=[self.config['backend_digest'],self.config['frontend_digest']]
            if previous not in pairs:pairs.append(previous)
        for name in ('backend', 'frontend'):
            digest = self.docker('image','inspect',self.config[name+'_image'],'--format','{{.Id}}')
            if not re.fullmatch(r'sha256:[a-f0-9]{64}',digest):
                raise ValueError('Approved image digest missing')
            capabilities[name+'_digest'] = digest
            self.config[name+'_digest'] = digest
        pair=[self.config['backend_digest'],self.config['frontend_digest']]
        if pair not in pairs:pairs.append(pair)
        if self.config.get('credential'):
            self.request('capabilities',capabilities);self.save_config();return
        response = self.request('register', {'token': self.config['registration_token'], 'capabilities': capabilities})
        self.config.update(response)
        self.config.pop('registration_token',None)
        self.save_config()

    def save_config(self):
        target = self.root / 'config.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.config))
        temporary.chmod(0o600)
        temporary.replace(target)

    @staticmethod
    def identifier(value):
        if not re.fullmatch(r'[a-f0-9]{32,64}',str(value)):
            raise ValueError('Invalid managed identifier')
        return value

    def containers(self, allocation):
        self.identifier(allocation)
        output = self.docker('ps','-aq','--filter','label=cloudagent.managed=true',
                             '--filter','label=cloudagent.allocation='+allocation)
        return output.splitlines() if output else []

    def health(self, backend, frontend):
        # Fixed in-container loopback health endpoints; no user-controlled URL or host networking.
        self.docker('exec',backend,'java','-Xmx32m','-cp','/app/health','Health',timeout=20)
        self.docker('exec',frontend,'wget','-q','-O','/dev/null','http://127.0.0.1:8080/health',timeout=8)

    def event(self, task, sequence, message):
        self.request('tasks/'+task['id']+'/events', {'attempt':task['attempt'],'sequence':sequence,'message':message})

    @staticmethod
    def redact_logs(text, environment):
        for entry in environment:
            name, _, value=entry.partition('=')
            if value and any(word in name.upper() for word in ('PASSWORD','SECRET','TOKEN','KEY')):
                text=text.replace(value,'[REDACTED]')
        text=re.sub(r'-----BEGIN [^-]+-----[\s\S]*?-----END [^-]+-----','[REDACTED KEY]',text)
        text=re.sub(r'(?i)((?:password|secret|token|authorization|api[_-]?key)\s*[=:]\s*)([^\s,;]+)',r'\1[REDACTED]',text)
        text=re.sub(r'(?i)(https?://)[^/@\s]+:[^/@\s]+@',r'\1[REDACTED]@',text)
        return text[-8000:]

    def execute(self, task):
        payload = task['payload']
        if isinstance(payload,str): payload=json.loads(payload)
        allocation = self.identifier(task['allocation_id'])
        kind = task['kind']
        if kind in ('CLEANUP','PAUSE','RESUME'):
            ids = self.containers(allocation)
            if kind == 'CLEANUP':
                for container in ids: self.docker('rm','-f',container)
                networks=self.docker('network','ls','-q','--filter','label=cloudagent.allocation='+allocation)
                for network in networks.splitlines():self.docker('network','rm',network)
                self.db.execute('DELETE FROM releases WHERE allocation=?',(allocation,));self.db.commit()
                # No application host mounts/volumes in this template. Other host data is untouched.
                return {'message':'Managed application containers removed; no persistent volumes used','evidence':'allocation-label inventory is empty','health':False}
            if kind == 'PAUSE':
                for container in ids: self.docker('stop','--time','10',container)
                return {'message':'Managed applications stopped'}
            releases=self.db.execute('SELECT backend,frontend FROM releases WHERE allocation=?',(allocation,)).fetchall()
            for backend,frontend in releases:
                self.docker('restart',backend);self.docker('restart',frontend)
                self.wait_health(backend,frontend)
            return {'message':'Managed applications resumed','health':True}
        project=self.identifier(task['project_id'])
        if payload.get('template')!='spring-react-single-host@1':raise ValueError('Template not approved')
        previous=self.db.execute('SELECT task,backend,frontend,port FROM releases WHERE project=?',(project,)).fetchone()
        if kind=='LOGS':
            if not previous:return {'message':'No active deployment','evidence':[]}
            state=json.loads(self.docker('inspect',previous[1]))[0]['State']
            frontend_state=json.loads(self.docker('inspect',previous[2]))[0]['State']
            environment=json.loads(self.docker('inspect',previous[1]))[0].get('Config',{}).get('Env',[])
            logs=self.redact_logs(self.docker('logs','--tail','60',previous[1]),environment)
            healthy=False
            if state['Running'] and frontend_state['Running']:
                try:self.health(previous[1],previous[2]);healthy=True
                except RuntimeError:pass
            return {'message':'Bounded redacted runtime evidence collected','health':healthy,'evidence':{'running':state['Running'] and frontend_state['Running'],'backend_running':state['Running'],'frontend_running':frontend_state['Running'],'exit_code':state['ExitCode'],'oom_killed':state.get('OOMKilled',False),'log_excerpt':logs}}
        if kind=='STOP':
            if not previous:raise ValueError('No deployed application')
            self.docker('stop','--timeout','10',previous[2]);self.docker('stop','--timeout','10',previous[1])
            return {'health':False,'message':'Application stopped; release and configuration retained; lease continues'}
        if kind=='START':
            if not previous:raise ValueError('No deployed application')
            self.docker('start',previous[1]);self.docker('start',previous[2]);self.wait_health(previous[1],previous[2])
            return {'health':True,'message':'Existing application release started'}
        if kind=='RESTART':
            if not previous:raise ValueError('No deployed application')
            self.docker('restart',previous[1]);self.docker('restart',previous[2]);self.wait_health(previous[1],previous[2])
            return {'health':True,'message':'Application restarted'}
        if kind not in ('DEPLOY','ROLLBACK'):raise ValueError('Action not approved')
        backend_image=payload['backend_image'];frontend_image=payload['frontend_image']
        if [backend_image,frontend_image] not in self.config.get('approved_pairs',[[self.config['backend_digest'],self.config['frontend_digest']]]):
            raise ValueError('Image pair not approved on host')
        suffix=self.identifier(task['id'])[:16]
        backend='ca-b-'+suffix;frontend='ca-f-'+suffix
        # One fixed app port on each exclusively allocated host. Existing other services are not touched.
        port=int(self.config.get('application_port',18080))
        backend_memory = int(self.config.get('backend_memory_mb', 512))
        if backend_memory not in (320, 512):
            raise ValueError('Unsupported approved backend memory profile')
        java_profile = ['--env', 'JAVA_TOOL_OPTIONS=-Xms32m -Xmx96m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=24m -XX:MaxDirectMemorySize=16m -Xss256k -XX:ActiveProcessorCount=1'] if backend_memory == 320 else []
        labels=['--label','cloudagent.managed=true','--label','cloudagent.allocation='+allocation,'--label','cloudagent.project='+project,'--label','cloudagent.deployment='+task['id']]
        network='ca-'+suffix
        environment=dict(payload.get('variables',{}));environment.update(task.get('execution_secrets',{}))
        for k,v in environment.items():
            if k not in ('APP_MESSAGE','API_TOKEN') or not isinstance(v,str) or '\n' in v or '\r' in v or len(v)>2000:
                raise ValueError('Environment configuration not approved')
        environment_file=self.root/('environment-'+suffix+'.env')
        fd=os.open(environment_file,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
        with os.fdopen(fd,'w') as f:f.write('\n'.join(k+'='+v for k,v in sorted(environment.items())))
        previous_stopped=False
        try:
            self.checkpoint(task)
            self.event(task,1,'PRECHECKING approved template and images')
            self.docker('network','create','--label','cloudagent.allocation='+allocation,'--label','cloudagent.deployment='+task['id'],network)
            self.checkpoint(task)
            self.event(task,2,'STARTING isolated candidate backend')
            self.docker('run','-d','--name',backend,*labels,'--network',network,'--network-alias','backend',
                '--restart','unless-stopped',
                '--memory',str(backend_memory)+'m','--memory-swap',str(backend_memory+256)+'m','--cpus','1','--pids-limit','128','--cap-drop','ALL','--security-opt','no-new-privileges',*java_profile,
                '--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--env-file',str(environment_file),backend_image)
            # Candidate backend is checked before releasing the old endpoint.
            for _ in range(25):
                self.checkpoint(task)
                try:self.docker('exec',backend,'java','-Xmx32m','-cp','/app/health','Health',timeout=20);break
                except RuntimeError:time.sleep(1)
            else:raise RuntimeError('Candidate backend health failed')
            # Server serializes this gate with user cancellation; after it, the UI
            # reports a non-cancellable cutover instead of claiming a false cancellation.
            self.checkpoint(task)
            try:self.request('tasks/'+task['id']+'/commit',{'attempt':task['attempt']})
            except urllib.error.HTTPError as error:
                if error.code==409:
                    try:code=json.load(error).get('code')
                    except (ValueError,TypeError):code=None
                    if code=='CANCEL_REQUESTED':raise DeploymentCancelled() from None
                raise
            if self._control:self._control['publishing']=True
            if previous:
                self.docker('stop','--timeout','5',previous[2]);previous_stopped=True
            self.docker('run','-d','--name',frontend,*labels,'--network',network,'--memory','128m','--cpus','0.5',
                '--restart','unless-stopped',
                '--pids-limit','64','--cap-drop','ALL','--security-opt','no-new-privileges','--publish',f'{port}:8080',frontend_image)
            self.event(task,3,'VERIFYING application HTTP health')
            self.wait_health(backend,frontend)
            self.db.execute('INSERT OR REPLACE INTO releases VALUES(?,?,?,?,?,?)',(project,task['id'],allocation,backend,frontend,port));self.db.commit()
            if previous:
                for old in previous[1:3]:
                    with contextlib.suppress(RuntimeError):self.docker('rm','-f',old)
            return {'health':True,'backend_digest':backend_image,'frontend_digest':frontend_image,
                'message':'HTTP health verified; application release published',
                'url':'http://'+self.config['public_host']+':'+str(port)}
        except DeploymentCancelled:
            try:self.remove_candidates(task)
            except RuntimeError:
                return {'manual_required':True,'message':'Cancellation requested but candidate cleanup is not confirmed; operator inspection required'}
            return {'cancelled':True,'candidate_cleanup_confirmed':True,'message':'Deployment cancelled before publication; previous release was not stopped'}
        except Exception:
            try:self.remove_candidates(task)
            except RuntimeError:
                return {'manual_required':True,'message':'Candidate cleanup could not be confirmed; operator inspection required'}
            if previous and previous_stopped:
                try:self.docker('start',previous[2])
                except RuntimeError:return {'manual_required':True,'message':'Previous endpoint recovery requires operator inspection'}
            raise
        finally:
            environment_file.unlink(missing_ok=True)

    def checkpoint(self,task):
        if self._control and self._control['cancel'].is_set():raise DeploymentCancelled()
        if self._control and self._control['invalid'].is_set():raise ExecutionInvalid('Execution authorization changed')
        status=self.request('tasks/'+task['id']+'/control',{'attempt':task['attempt']})
        if status.get('cancel_requested'):raise DeploymentCancelled()

    def remove_candidates(self,task):
        allocation=self.identifier(task['allocation_id']);deployment=self.identifier(task['id'])
        filters=['--filter','label=cloudagent.allocation='+allocation,'--filter','label=cloudagent.deployment='+deployment]
        ids=self.docker('ps','-aq',*filters).splitlines()
        for container in ids:self.docker('rm','-f',container)
        for network in self.docker('network','ls','-q',*filters).splitlines():self.docker('network','rm',network)
        if self.docker('ps','-aq',*filters).strip() or self.docker('network','ls','-q',*filters).strip():
            raise RuntimeError('Candidate resources remain')

    def execute_controlled(self,task):
        if task.get('kind') not in ('DEPLOY','ROLLBACK'):return self.execute(task)
        context={'cancel':threading.Event(),'invalid':threading.Event(),'stop':threading.Event(),'publishing':False}
        self._control=context
        def monitor():
            while not context['stop'].wait(1):
                try:
                    status=self.request('tasks/'+task['id']+'/control',{'attempt':task['attempt']})
                    if status.get('commit_started'):return
                    if status.get('cancel_requested'):context['cancel'].set();return
                except urllib.error.HTTPError as error:
                    if error.code in (401,403,404,409):context['invalid'].set();return
                except Exception:pass # The synchronous publication gate still fails closed.
        watcher=threading.Thread(target=monitor,daemon=True);watcher.start()
        try:return self.execute(task)
        finally:
            context['stop'].set();watcher.join(timeout=.2);self._control=None

    def wait_health(self,backend,frontend):
        for _ in range(25):
            try:self.health(backend,frontend);return
            except RuntimeError:time.sleep(1)
        raise RuntimeError('Application health check failed')

    def tick(self):
        data=self.request('tasks/claim',{})
        task=data.get('task')
        if not task:return
        row=self.db.execute('SELECT attempt,state,result FROM receipts WHERE id=?',(task['id'],)).fetchone()
        if row and row[0]!=task['attempt'] and row[1]=='DONE' and task.get('kind')=='CLEANUP' and row[2] and not json.loads(row[2]).get('success'):
            # 只有管理员批准的已失败清理允许新attempt；未知/中断的发布不能自动重放。
            self.db.execute('DELETE FROM receipts WHERE id=?',(task['id'],));self.db.commit();row=None
        if row:
            if row[0]!=task['attempt']:raise RuntimeError('Execution attempt changed; manual reconciliation required')
            result=json.loads(row[2]) if row[2] else {'success':False,'result':{'manual_required':True,'message':'Interrupted execution requires operator inspection'}}
        else:
            self.db.execute('INSERT INTO receipts VALUES(?,?,?,NULL)',(task['id'],task['attempt'],'RUNNING'));self.db.commit()
            try:
                output=self.execute_controlled(task)
                result={'success':not (output.get('cancelled') or output.get('manual_required')),'result':output}
            except Exception as exc:
                print('Execution failed:',type(exc).__name__,str(exc) if isinstance(exc,(ValueError,RuntimeError)) else '',flush=True)
                result={'success':False,'result':{'message':str(exc) if isinstance(exc,(ValueError,RuntimeError)) else 'Deployment failed; inspect controlled execution evidence'}}
            self.db.execute('UPDATE receipts SET state=?,result=? WHERE id=?',('DONE',json.dumps(result),task['id']));self.db.commit()
        self.request('tasks/'+task['id']+'/complete',{'attempt':task['attempt'],**result})


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--config');args=parser.parse_args()
    root=Path(args.root).resolve();root.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (root/'runner.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        config_path=root/'config.json'
        config=json.loads(config_path.read_text() if config_path.exists() else Path(args.config).read_text())
        if not config['base_url'].startswith('https://') and not config.get('allow_local_http'):
            raise ValueError('TLS required; local HTTP must be explicitly enabled for the demo')
        runner=Runner(config,root);runner.register()
        def heartbeat():
            while True:
                try:runner.request('heartbeat',{})
                except Exception:pass
                time.sleep(10)
        threading.Thread(target=heartbeat,daemon=True).start()
        while True:
            try:runner.tick()
            except Exception as exc:print('Runner request/execution unavailable:',type(exc).__name__,flush=True)
            time.sleep(2)

if __name__=='__main__':main()
