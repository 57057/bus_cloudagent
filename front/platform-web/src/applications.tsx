import React,{useEffect,useState,useRef} from 'react';
import {api,key,date,Row} from './api';
import {objectValue,operationNames} from './application-ui';
import {ApplicationWorkspace} from './application-workspace';
import {AdminApplications} from './admin-applications';

export function Applications({ctx,admin=false}:{ctx:any;admin?:boolean}){return admin?<AdminApplications ctx={ctx}/>:<ApplicationWorkspace/>}

export function ApplicationPlanInbox({ctx}:{ctx:any}){
 const [plans,setPlans]=useState<Row[]>([]),[busy,setBusy]=useState('');
 const keys=useRef<Record<string,string>>({});
 useEffect(()=>{let gone=false;let timer:ReturnType<typeof setTimeout>;const poll=async()=>{try{if(!document.hidden){const rows=await api('/application-plans');if(!gone)setPlans(rows)}}catch{}finally{if(!gone)timer=setTimeout(poll,8000)}};void poll();return()=>{gone=true;clearTimeout(timer)}},[]);
 return <>{plans.map(plan=><div className="panel" key={plan.id}><h3>AI 操作建议 · {operationNames[plan.kind]||plan.kind}</h3><p>{objectValue(plan.snapshot).impact}。有效期至 {date(plan.expires_at)}。</p><div className="actions"><button disabled={!!busy} onClick={()=>{setBusy(plan.id);void ctx.run(async()=>{try{keys.current[plan.id]??=key();await api(`/plans/${plan.id}/confirm`,'POST',{planHash:plan.plan_hash,confirmed:true},keys.current[plan.id]);setPlans(v=>v.filter(p=>p.id!==plan.id));ctx.notice('操作已提交，可在“我的应用”查看进度')}finally{setBusy('')}})}}>确认执行</button><button className="secondary" disabled={!!busy} onClick={()=>{setBusy(plan.id);void ctx.run(async()=>{try{await api(`/plans/${plan.id}/cancel`,'POST');setPlans(v=>v.filter(p=>p.id!==plan.id))}finally{setBusy('')}})}}>放弃建议</button></div></div>)}</>;
}

/** 应用入口与聊天独立，模型无法替代此处的显式计划确认。 */
