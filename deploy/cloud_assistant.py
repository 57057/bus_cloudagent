"""Administrative ECS transport for existing-instance inspection and frontend deployment.

Never registered as an Agent/MCP tool. Does not support cloud purchases.
"""
import base64,datetime,hashlib,hmac,time,uuid
from pathlib import Path
from urllib.parse import quote
import requests
from dotenv import dotenv_values
ROOT=Path(__file__).resolve().parents[1]
ALLOWED={'DescribeInstances','RunCommand','DescribeInvocationResults','DescribeSecurityGroupAttribute','AuthorizeSecurityGroup','DescribeInstanceMonitorData','GetInstanceScreenshot','RebootInstance'}

def call(action,values):
    if action not in ALLOWED:raise ValueError('Cloud action is not permitted by this deployer')
    if action=='RebootInstance' and (values.get('InstanceId')!='i-bp17py7klx59epbxm93r' or str(values.get('ForceStop')).lower()!='false'):
        raise ValueError('Only graceful recovery of the authorized ECS is supported')
    settings=dotenv_values(ROOT/'business-java/.env')
    parameters={'Action':action,'Version':'2014-05-26','Format':'JSON','RegionId':'cn-hangzhou',
        'AccessKeyId':settings['ALIYUN_ACCESS_KEY_ID'],'SignatureMethod':'HMAC-SHA1','SignatureVersion':'1.0',
        'SignatureNonce':uuid.uuid4().hex,'Timestamp':datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),**values}
    encode=lambda value:quote(str(value),safe='~')
    canonical='&'.join(encode(k)+'='+encode(parameters[k]) for k in sorted(parameters))
    parameters['Signature']=base64.b64encode(hmac.new((settings['ALIYUN_ACCESS_KEY_SECRET']+'&').encode(),('POST&%2F&'+encode(canonical)).encode(),hashlib.sha1).digest()).decode()
    response=requests.post('https://ecs.aliyuncs.com/',data=parameters,timeout=35);data=response.json()
    if 'Code' in data:raise RuntimeError('Cloud request rejected: '+data['Code']+' requestId='+data.get('RequestId',''))
    return data

def command(instance,script,name='cloudagent-static-deploy',timeout=180,command_type='RunPowerShellScript'):
    if command_type not in ('RunPowerShellScript','RunShellScript'):
        raise ValueError('Unsupported Cloud Assistant command type')
    data=call('RunCommand',{'InstanceId.1':instance,'Type':command_type,'Name':name,
        'CommandContent':base64.b64encode(script.encode()).decode(),'Timeout':str(timeout),'ContentEncoding':'Base64'})
    invocation=data['InvokeId']
    for _ in range((timeout+30)//2):
        result=call('DescribeInvocationResults',{'InvokeId':invocation,'InstanceId':instance,'ContentEncoding':'Base64'})
        rows=result.get('Invocation',{}).get('InvocationResults',{}).get('InvocationResult',[])
        if rows and rows[0].get('InvocationStatus') in ('Success','Failed','Error','Timeout','Cancelled','Aborted','Invalid'):
            row=rows[0];raw=base64.b64decode(row.get('Output',''))
            try:output=raw.decode('utf-8')
            except UnicodeDecodeError:output=raw.decode('gb18030',errors='replace')
            return {'status':row['InvocationStatus'],'exit_code':row.get('ExitCode'),'output':output}
        time.sleep(2)
    raise TimeoutError('Inspect Cloud Assistant invocation '+invocation+' before retrying')
