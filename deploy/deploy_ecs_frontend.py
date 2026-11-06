"""Deploy the built static frontend to an explicitly configured Windows ECS via Cloud Assistant.

No SSH credentials, arbitrary user scripts, cloud purchases, or local backend
exposure. Only the static build is transferred; no .env or server credentials.
"""
from pathlib import Path
import base64,concurrent.futures,hashlib,json,sys,uuid,zipfile,io
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))

def main():
    # Local private API transport holds the AK only in memory; never transfer it to the ECS.
    from deploy.cloud_assistant import call,command
    target=json.loads((ROOT/'runtime/ecs-target.json').read_text());instance=target['InstanceId']
    current=call('DescribeInstances',{'InstanceIds':json.dumps([instance])})['Instances']['Instance']
    if len(current)!=1 or current[0]['Status']!='Running':raise ValueError('Target instance is not running')
    target=current[0]
    if target.get('OSType','').lower()!='windows':raise ValueError('This deployer only supports Windows static sites')
    release=uuid.uuid4().hex;remote='C:\\ProgramData\\CloudAgent\\frontend\\releases\\'+release
    memory=io.BytesIO()
    with zipfile.ZipFile(memory,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for file in (ROOT/'front/platform-web/dist').rglob('*'):
            if file.is_file() and file.name!='deployment-config.js':
                if file.suffix.lower() not in ('.html','.js','.css','.svg','.png','.jpg','.jpeg','.webp','.ico','.woff','.woff2','.ttf'):raise ValueError('Unexpected non-static file in build output')
                archive.write(file,file.relative_to(ROOT/'front/platform-web/dist').as_posix())
        archive.writestr('deployment-config.js','window.__CLOUDAGENT_DEPLOYMENT__ = {frontendOnly:true};\n')
        archive.writestr('web.config','''<?xml version="1.0" encoding="utf-8"?><configuration><system.webServer><defaultDocument><files><clear/><add value="index.html"/></files></defaultDocument><httpProtocol><customHeaders><add name="X-Content-Type-Options" value="nosniff"/></customHeaders></httpProtocol></system.webServer></configuration>''')
    raw=memory.getvalue();digest=hashlib.sha256(raw).hexdigest();encoded=base64.b64encode(raw).decode()
    def run(script,name):
        result=command(instance,"$ErrorActionPreference='Stop'; "+script,name=name,timeout=180)
        if result['status']!='Success' or result['exit_code']!=0:raise RuntimeError(result['output'][:1500])
        return result['output']
    run("New-Item -ItemType Directory -Force -Path '"+remote+"' | Out-Null",'cloudagent-create-release')
    chunks=[encoded[i:i+7000] for i in range(0,len(encoded),7000)]
    def upload(item):
        index,chunk=item
        run("[IO.File]::WriteAllText('"+remote+f"\\part-{index:04d}.txt','"+chunk+"',[Text.Encoding]::ASCII)",'cloudagent-upload-static')
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:list(executor.map(upload,enumerate(chunks)))
    script="""
$release='%s'; $expected='%s';
$parts=Get-ChildItem -LiteralPath $release -Filter 'part-*.txt' | Sort-Object Name;
$encoded=($parts | ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join '';
$zip=Join-Path $release 'frontend.zip'; [IO.File]::WriteAllBytes($zip,[Convert]::FromBase64String($encoded));
if((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower() -ne $expected){throw 'Artifact hash mismatch'};
$siteRoot=Join-Path $release 'site'; Expand-Archive -LiteralPath $zip -DestinationPath $siteRoot;
& icacls.exe $siteRoot /grant 'IIS_IUSRS:(OI)(CI)(RX)' /T | Out-Null;
Import-Module WebAdministration;
if(Test-Path 'IIS:\Sites\Default Web Site'){
  $default=Get-Item 'IIS:\Sites\Default Web Site';
  if($default.physicalPath -notin @('%%SystemDrive%%\inetpub\wwwroot','C:\inetpub\wwwroot')){throw 'Existing default site is not the new IIS placeholder; refusing to stop it'};
  Stop-Website -Name 'Default Web Site'
};
if(!(Test-Path 'IIS:\AppPools\CloudAgentFrontend')){New-WebAppPool -Name 'CloudAgentFrontend' | Out-Null};
Set-ItemProperty 'IIS:\AppPools\CloudAgentFrontend' -Name managedRuntimeVersion -Value '';
$previousRoot=$null;if(Test-Path 'IIS:\Sites\CloudAgentFrontend'){$previousRoot=(Get-Item 'IIS:\Sites\CloudAgentFrontend').physicalPath};
if(Test-Path 'IIS:\Sites\CloudAgentFrontend'){Set-ItemProperty 'IIS:\Sites\CloudAgentFrontend' -Name physicalPath -Value $siteRoot}
else{New-Website -Name 'CloudAgentFrontend' -Port 80 -PhysicalPath $siteRoot -ApplicationPool 'CloudAgentFrontend' | Out-Null};
if(!(Get-NetFirewallRule -DisplayName 'CloudAgent Frontend HTTP' -ErrorAction SilentlyContinue)){New-NetFirewallRule -DisplayName 'CloudAgent Frontend HTTP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80 | Out-Null};
Start-Website -Name 'CloudAgentFrontend';
try{$response=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1/' -TimeoutSec 15; if($response.StatusCode -ne 200){throw 'Frontend health failed'}}
catch{if($previousRoot){Set-ItemProperty 'IIS:\Sites\CloudAgentFrontend' -Name physicalPath -Value $previousRoot;Start-Website -Name 'CloudAgentFrontend'};throw};
@{status=$response.StatusCode; release=$release; previewOnly=$true} | ConvertTo-Json -Compress
"""%(remote,digest)
    print(run(script,'cloudagent-publish-frontend'))
    group=target['SecurityGroupIds']['SecurityGroupId'][0]
    rules=call('DescribeSecurityGroupAttribute',{'SecurityGroupId':group}).get('Permissions',{}).get('Permission',[])
    if not any(p.get('IpProtocol','').lower()=='tcp' and p.get('PortRange')=='80/80' and p.get('SourceCidrIp')=='0.0.0.0/0' for p in rules):
        call('AuthorizeSecurityGroup',{'SecurityGroupId':group,'IpProtocol':'tcp','PortRange':'80/80','SourceCidrIp':'0.0.0.0/0','Policy':'accept','Description':'CloudAgent static frontend HTTP only'})
    report={'instance_id':instance,'release':release,'remote_directory':remote,'artifact_sha256':digest,'url':'http://'+target['PublicIpAddress']['IpAddress'][0]+'/','frontend_only':True,'transport':'ALIYUN_CLOUD_ASSISTANT','os':'WINDOWS','cloud_purchased':False}
    (ROOT/'runtime/ecs-frontend-deployment.json').write_text(json.dumps(report,indent=2));print('Static frontend published:',report['url'])

if __name__=='__main__':main()
