import React,{useEffect,useRef,useState} from 'react';
import {api,key,date,Row} from './api';
import {objectValue,operationNames,runtimeNames,safeApplicationUrl,taskLabel,stageFor,readableEvent} from './application-ui';
import './applications.css';

const active=(t:Row)=>['QUEUED','RUNNING','MANUAL_REQUIRED'].includes(t.state);
const errorText=(e:unknown)=>e instanceof Error?e.message:String(e);

export function ApplicationWorkspace(){
 const [projects,setProjects]=useState<Row[]>([]),[allocations,setAllocations]=useState<Row[]>([]);
 const [selectedId,setSelectedId]=useState(()=>sessionStorage.getItem('cloudagent.application')||'');
 const [detail,setDetail]=useState<Row|null>(null),[events,setEvents]=useState<Row[]>([]);
 const [loading,setLoading]=useState(true),[error,setError]=useState(''),[busy,setBusy]=useState('');
 const [showCreate,setShowCreate]=useState(false),[showHistory,setShowHistory]=useState(false);
 const [name,setName]=useState('我的第一个应用'),[allocationId,setAllocationId]=useState('');
 const [message,setMessage]=useState(''),[savedMessage,setSavedMessage]=useState(''),[rollback,setRollback]=useState('');
 const [secret,setSecret]=useState(''),[replaceSecret,setReplaceSecret]=useState(false);
 const [plan,setPlan]=useState<Row|null>(null),[cancelTarget,setCancelTarget]=useState<Row|null>(null),[previewVersion,setPreviewVersion]=useState(0);
 const confirmKey=useRef(''),version=useRef(0),pollDelay=useRef(3000);
 const liveProjects=projects.filter(p=>p.allocation_state==='ACTIVE');
 const shown=showHistory?projects:liveProjects;
 const free=allocations.filter(a=>a.state==='ACTIVE'&&!projects.some(p=>p.allocation_id===a.id));
 const recent:Row[]=detail?.recent_deployments||[];
 const running=recent.find(active);
 const editable=detail?.allocation_state==='ACTIVE'&&detail?.instance_state==='RUNNING';
 const blocked=!!busy||!!running||!editable;
 const current=detail?.current_release;
 const url=safeApplicationUrl(current?.url);
 const stopped=detail?.runtime_state==='STOPPED';
 const canVisit=!!url&&!stopped&&editable;
 const canFrame=canVisit&&new URL(url).origin!==window.location.origin&&!(window.location.protocol==='https:'&&url.startsWith('http:'));

 async function lists(){const [p,a]=await Promise.all([api('/projects'),api('/allocations')]);setProjects(p);setAllocations(a);return p as Row[]}
 async function refreshDetail(id:string,includeConfig=false){
  const revision=version.current,d=await api(`/projects/${id}`),t=(d.recent_deployments||[])[0];
  const logs=t?await api(`/deployments/${t.id}/events`):[];
  const c=includeConfig&&d.instance_state==='RUNNING'&&d.allocation_state==='ACTIVE'?await api(`/projects/${id}/config`):null;
  if(revision!==version.current)return;
  setDetail(d);setEvents(logs);setProjects(rows=>rows.map(p=>p.id===id?{...p,...d}:p));
  pollDelay.current=(d.recent_deployments||[]).some((t:Row)=>['QUEUED','RUNNING'].includes(t.state))?2000:12000;
  if(c){const text=String(objectValue(c.variables).APP_MESSAGE||'');setMessage(text);setSavedMessage(text)}
 }
 async function act(label:string,work:()=>Promise<void>){setBusy(label);setError('');try{await work()}catch(e){setError(errorText(e))}finally{setBusy('')}}
 useEffect(()=>{let gone=false;void lists().then(p=>{if(!gone)setSelectedId(old=>p.some(v=>v.id===old&&v.allocation_state==='ACTIVE')?old:p.find(v=>v.allocation_state==='ACTIVE')?.id||'')}).catch(e=>{if(!gone)setError(errorText(e))}).finally(()=>{if(!gone)setLoading(false)});return()=>{gone=true}},[]);
 useEffect(()=>{
  version.current++;setDetail(null);setEvents([]);setPlan(null);setMessage('');setSavedMessage('');setRollback('');setSecret('');setReplaceSecret(false);
  if(!selectedId)return;
  sessionStorage.setItem('cloudagent.application',selectedId);
  let gone=false;let timer:ReturnType<typeof setTimeout>;
  const poll=async(first=false)=>{try{if(first||!document.hidden)await refreshDetail(selectedId,first)}catch(e){if(!gone)setError(errorText(e))}finally{if(!gone)timer=setTimeout(()=>void poll(),pollDelay.current)}};
  void poll(true);return()=>{gone=true;clearTimeout(timer);version.current++};
 },[selectedId]);
 async function prepare(kind:string){await act(kind,async()=>{
  if(kind==='DEPLOY'&&message!==savedMessage){await api(`/projects/${selectedId}/welcome`,'PATCH',{message});setSavedMessage(message)}
  const p=await api(`/projects/${selectedId}/plans`,'POST',{kind,...(kind==='ROLLBACK'?{targetDeploymentId:rollback}:{})});
  confirmKey.current=key();setPlan({...p,kind});
 })}
 const latest=recent[0];
 const cancellable=latest&&['DEPLOY','ROLLBACK'].includes(latest.kind)&&['QUEUED','RUNNING'].includes(latest.state)&&!latest.commit_started&&!latest.cancel_requested;
 return <div className="application-workspace">
  <div className="app-heading"><div><span className="app-eyebrow">从创建到上线，一步一步完成</span><h1>我的应用</h1><p>选好资源，写一句欢迎语，即可发布一个真正可以访问的网站。</p></div><button disabled={!!busy} onClick={()=>setShowCreate(v=>!v)}>＋ 创建应用</button></div>
  {error&&<div className="app-error" role="alert">{error}<button className="ghost" onClick={()=>setError('')}>关闭</button></div>}
  {!loading&&!allocations.some(a=>a.state==='ACTIVE')&&<div className="app-callout app-section-heading"><div><strong>先准备一项部署资源</strong><p>你还没有有效的资源租约。先选购部署套餐，再回来创建应用。</p></div><a className="button" href="#/products">选择部署套餐 →</a></div>}
  <ol className="app-steps">{[['准备资源','租用可部署的资源',allocations.some(a=>a.state==='ACTIVE')],['创建应用','取一个容易识别的名字',projects.length>0],['发布版本','确认配置并等待部署',!!current],['查看效果','打开网站或在下方预览',canVisit]].map(([title,description,done],i)=><li key={String(title)} className={done?'complete':''}><span>{done?'✓':i+1}</span><div><strong>{title}</strong><small>{description}</small></div></li>)}</ol>
  {showCreate&&<form className="panel" onSubmit={e=>{e.preventDefault();void act('create',async()=>{const p=await api('/projects','POST',{name:name.trim(),allocationId});await lists();setSelectedId(p.id);setShowCreate(false)})}}><h2>创建你的应用</h2><p>当前提供“欢迎页面”模板，包含网站页面和后端服务；暂不支持上传任意项目源码。</p><div className="app-form-grid"><label className="field">应用名称<input required maxLength={80} value={name} onChange={e=>setName(e.target.value)}/></label><label className="field">用于部署的资源<select required value={allocationId} onChange={e=>setAllocationId(e.target.value)}><option value="">请选择一项可用资源</option>{free.map(a=><option key={a.id} value={a.id}>{a.instance_name||a.name||'可用资源'} · 到期 {date(a.expires_at)}</option>)}</select></label></div>{free.length===0&&<p>还没有空闲资源。请先前往<a href="#/products">服务市场</a>租用资源，或选择下方已有应用。</p>}<div className="actions"><button disabled={!!busy||!allocationId||!name.trim()}>创建应用</button><button type="button" className="secondary" onClick={()=>setShowCreate(false)}>稍后创建</button></div></form>}
  <div className="app-section-heading"><h2>应用列表 <small>{liveProjects.length} 个有效应用</small></h2><button className="ghost" onClick={()=>setShowHistory(v=>!v)}>{showHistory?'隐藏已结束的租约':'查看历史应用'}</button></div>
  {loading?<div className="panel">正在读取你的应用…</div>:shown.length===0?<div className="panel app-empty"><span>▧</span><h2>从你的第一个网站开始</h2><p>创建应用后，就能在这里部署、查看效果和停止运行。</p><button onClick={()=>setShowCreate(true)}>创建第一个应用</button></div>:<div className="app-cards">{shown.map(p=><button className={'app-card '+(selectedId===p.id?'selected':'')} key={p.id} disabled={!!busy||p.allocation_state!=='ACTIVE'} onClick={()=>setSelectedId(p.id)}><div><strong>{p.name}</strong><span className={'app-badge '+(p.runtime_state==='RUNNING'?'online':'')}>{p.allocation_state!=='ACTIVE'?'租约已结束':runtimeNames[p.runtime_state]||'等待检查'}</span></div><p>{p.current_deployment_id?'已有已发布版本':'尚未发布，选中后开始部署'}</p><small>资源到期 {date(p.expires_at)}</small></button>)}</div>}
  {selectedId&&!detail&&<div className="panel">正在读取应用详情…</div>}
  {detail&&<>
   <div className="app-section-heading"><div><h2>{detail.name}</h2><p>资源到期 {date(detail.expires_at)}{detail.host_mode==='SHARED_DEMO'?' · 共享主机演示环境':''}</p></div><span className={'app-badge '+(detail.runtime_state==='RUNNING'?'online':'')}>{runtimeNames[detail.runtime_state]||'等待检查'}</span></div>
   {!editable&&<div className="app-callout">当前资源未运行或租约已结束，暂时无法部署和访问应用。请到<a href="#/instances">我的资源</a>查看状态。</div>}
   <div className="app-editor-preview"><section className="panel"><span className="app-eyebrow">01 / 配置与发布</span><h2>{current?'更新网站内容':'准备你的网站'}</h2><p>欢迎语会显示在部署后的网站上。修改后点击部署，才会更新已上线的页面。</p><label className="field">网站欢迎语<textarea rows={4} maxLength={500} value={message} disabled={blocked} onChange={e=>setMessage(e.target.value.replace(/[\r\n]+/g,' '))} placeholder="你好，欢迎来到我的网站！"/></label><small>{message.length}/500 字 · 请勿填写密码或个人隐私</small><div className="actions app-primary-actions"><button disabled={blocked} onClick={()=>void prepare('DEPLOY')}>{busy==='DEPLOY'?'正在准备…':current?'部署更新':'开始部署'}</button>{current&&<button className={stopped?'secondary':'danger'} disabled={blocked||!detail.runner_controls_supported} onClick={()=>void prepare(stopped?'START':'STOP')}>{stopped?'启动应用':'停止应用'}</button>}</div>{running&&<p className="app-muted">已有操作正在处理，请先查看下方进度。</p>}{current&&<small>停止应用保留版本与配置，可再次启动；租约及计费继续。</small>}</section>
    <section className="panel app-preview-panel"><div className="app-section-heading"><div><span className="app-eyebrow">02 / 实际效果</span><h2>你的网站</h2></div>{canVisit&&<button className="ghost" onClick={()=>setPreviewVersion(v=>v+1)}>刷新预览</button>}</div>{canVisit?<><div className="app-url"><a href={url} target="_blank" rel="noopener noreferrer">{url}</a><button className="secondary" onClick={()=>void act('copy',async()=>{await navigator.clipboard.writeText(url)})}>复制地址</button><a className="button" href={url} target="_blank" rel="noopener noreferrer">打开网站 ↗</a></div>{canFrame?<iframe key={url+current?.id+previewVersion} src={url} title="已部署应用的实际页面" sandbox="allow-scripts allow-same-origin allow-forms" referrerPolicy="no-referrer"/>:<div className="app-preview-empty">请点击“打开网站”在新窗口查看实际效果。</div>}<small>这是已发布版本的实际页面。预览为空时，请在新窗口打开确认网络状态。</small></>:<div className="app-preview-empty"><span>{stopped?'Ⅱ':'▧'}</span><h3>{stopped?'应用已停止':current?'资源暂不可用':'部署后，在这里查看实际效果'}</h3><p>{stopped?'点击“启动应用”即可恢复当前版本。':'完成部署后会显示访问地址，并加载你真实的网站页面。'}</p></div>}</section></div>
   {latest&&<section className="panel app-progress" aria-live="polite"><div className="app-section-heading"><h2>{operationNames[latest.kind]||latest.kind} · {taskLabel(latest)}</h2>{cancellable&&<button className="danger" disabled={!!busy} onClick={()=>setCancelTarget(latest)}>取消本次部署</button>}</div>{['DEPLOY','ROLLBACK'].includes(latest.kind)&&<ol className="app-track">{['等待执行','检查环境','启动新版本','验证并发布'].map((label,i)=><li key={label} className={stageFor(latest,events)>i?'done':stageFor(latest,events)===i?'current':''}>{label}</li>)}</ol>}{latest.cancel_requested&&latest.state==='RUNNING'&&<p>取消请求已送达，正在清理本次候选版本。清理完成后才会显示“已取消”，原已发布版本保持不变。</p>}{latest.commit_started&&latest.state==='RUNNING'&&<p>新版本正在进行最终切换，此阶段暂不可取消。完成后可以停止应用或恢复历史版本。</p>}{latest.state==='CANCELLED'&&<p>本次部署已取消，没有发布新版本。</p>}{latest.state==='FAILED'&&<p>操作没有完成，已保留历史发布记录。可检查运行状态后重试；仍无法恢复时请联系管理员。</p>}{latest.state==='MANUAL_REQUIRED'&&<p>执行结果需要管理员核实，暂时禁止继续操作，避免重复部署。</p>}<details><summary>查看执行详情</summary>{events.map((e,i)=><p key={i}>{readableEvent(String(e.message))}</p>)}<pre>{JSON.stringify(objectValue(latest.result),null,2)}</pre><small>任务编号 {latest.id} · {date(latest.created_at)}</small></details></section>}
   <details className="panel app-advanced"><summary>更多操作与历史记录</summary><p>检查状态不会修改网站。重启和恢复历史版本会暂时影响访问。</p><div className="actions"><button className="secondary" disabled={blocked||!current} onClick={()=>void prepare('LOGS')}>检查运行状态</button><button className="secondary" disabled={blocked||!current||stopped} onClick={()=>void prepare('RESTART')}>重启当前版本</button></div><label className="field">恢复一个历史版本<select value={rollback} disabled={blocked} onChange={e=>setRollback(e.target.value)}><option value="">请选择已成功发布的版本</option>{recent.filter(t=>t.state==='SUCCEEDED'&&['DEPLOY','ROLLBACK'].includes(t.kind)&&t.id!==current?.id).map(t=><option key={t.id} value={t.id}>{date(t.created_at)} · {t.id.slice(0,8)}</option>)}</select></label><button className="secondary" disabled={blocked||!rollback} onClick={()=>void prepare('ROLLBACK')}>预览恢复操作</button><details><summary>应用访问密钥</summary><p>完整替换应用密钥；留空将移除已有密钥，保存后需要重新部署。只修改上方欢迎语会保留已有密钥。</p><label className="field">新的访问密钥<input type="password" autoComplete="new-password" value={secret} disabled={blocked} onChange={e=>setSecret(e.target.value)}/></label><label><input type="checkbox" checked={replaceSecret} disabled={blocked} onChange={e=>setReplaceSecret(e.target.checked)}/> 我确认替换现有应用密钥</label><div className="actions"><button className="secondary" disabled={blocked||!replaceSecret} onClick={()=>void act('config',async()=>{await api(`/projects/${selectedId}/config`,'POST',{variables:{APP_MESSAGE:message},secrets:secret?{API_TOKEN:secret}:{}});setSavedMessage(message);setSecret('');setReplaceSecret(false);await refreshDetail(selectedId)})}>保存密钥配置</button></div></details><div className="app-history">{recent.map(t=><div key={t.id}><strong>{operationNames[t.kind]||t.kind}</strong><span>{taskLabel(t)}</span><small>{date(t.created_at)}</small></div>)}</div><small>项目编号 {detail.id}</small></details>
  </>}
  {plan&&<div className="app-overlay"><section className="panel app-dialog" role="dialog" aria-modal="true" aria-labelledby="plan-title">{error&&<p role="alert" className="app-error">{error}</p>}<h2 id="plan-title">确认{operationNames[plan.kind]}</h2><p>应用：<strong>{detail?.name}</strong></p><p>{objectValue(plan.snapshot).impact}</p>{plan.kind==='DEPLOY'&&<div className="app-callout">本次发布的欢迎语：{String(objectValue(objectValue(plan.snapshot).variables).APP_MESSAGE||'使用模板默认欢迎语')}</div>}<details><summary>查看技术详情</summary><pre>{JSON.stringify(plan.snapshot,null,2)}</pre></details><div className="actions"><button disabled={!!busy} onClick={()=>void act('confirm',async()=>{await api(`/plans/${plan.id}/confirm`,'POST',{planHash:plan.plan_hash,confirmed:true},confirmKey.current);setPlan(null);await refreshDetail(selectedId);await lists()})}>{busy==='confirm'?'正在提交…':'确认执行'}</button><button className="secondary" disabled={!!busy} onClick={()=>void act('dismiss',async()=>{await api(`/plans/${plan.id}/cancel`,'POST');setPlan(null)})}>返回修改</button></div></section></div>}
  {cancelTarget&&<div className="app-overlay"><section className="panel app-dialog" role="dialog" aria-modal="true" aria-labelledby="cancel-title">{error&&<p role="alert" className="app-error">{error}</p>}<h2 id="cancel-title">取消本次部署？</h2><p>将停止准备新版本并清理本次候选资源。已经上线的版本不会因此停止，租约也会保留。</p><p>若已进入最终发布切换阶段，系统会提示无法取消；可在发布完成后停止应用。</p><div className="actions"><button className="danger" disabled={!!busy} onClick={()=>void act('cancel',async()=>{await api(`/deployments/${cancelTarget.id}/cancel`,'POST',{confirmed:true});setCancelTarget(null);await refreshDetail(selectedId)})}>确认取消部署</button><button className="secondary" disabled={!!busy} onClick={()=>setCancelTarget(null)}>继续等待</button></div></section></div>}
 </div>;
}
