import type {Row} from './api';
export const operationNames:Record<string,string>={DEPLOY:'部署新版本',ROLLBACK:'恢复历史版本',STOP:'停止应用',START:'启动应用',RESTART:'重启应用',LOGS:'检查运行状态'};
export const runtimeNames:Record<string,string>={NOT_DEPLOYED:'尚未部署',RUNNING:'运行中',STOPPED:'已停止',UNKNOWN:'等待检查'};
export function objectValue(value:unknown):Row{
 if(value && typeof value==='object')return value as Row;
 try{return typeof value==='string'?JSON.parse(value)||{}:{}}catch{return {}}
}
export function taskLabel(task:Row):string{
 if(task.state==='RUNNING'&&task.cancel_requested)return '正在取消，等待清理完成';
 if(task.state==='RUNNING'&&task.commit_started)return '正在切换到新版本';
 return ({QUEUED:'排队中',RUNNING:'执行中',SUCCEEDED:'已完成',FAILED:'未完成，请查看详情',CANCELLED:'已取消',MANUAL_REQUIRED:'需要管理员处理'} as Record<string,string>)[task.state]||'等待更新';
}
export function safeApplicationUrl(value:unknown):string{
 if(typeof value!=='string')return '';
 try{const url=new URL(value);return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:''}catch{return ''}
}
export function stageFor(task:Row,events:Row[]):number{
 if(task.state==='SUCCEEDED')return 4;
 if(task.commit_started)return 3;
 if(events.some(e=>String(e.message).includes('STARTING')))return 2;
 return task.state==='QUEUED'?0:1;
}
export function readableEvent(message:string):string{
 if(message.includes('PRECHECKING'))return '正在检查部署环境';
 if(message.includes('STARTING'))return '正在启动新版本，当前应用继续提供服务';
 if(message.includes('VERIFYING'))return '正在检查应用能否正常访问';
 return message;
}
