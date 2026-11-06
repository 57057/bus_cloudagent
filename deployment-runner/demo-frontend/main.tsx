import React,{useEffect,useState} from 'react';
import {createRoot} from 'react-dom/client';
function App(){const [data,setData]=useState<any>(null);useEffect(()=>{fetch('/api/hello').then(r=>r.json()).then(setData).catch(()=>setData({message:'后端暂不可达'}))},[]);
return <main style={{fontFamily:'system-ui',maxWidth:820,margin:'12vh auto',padding:32}}><small>CLOUD STACK · DEPLOYMENT</small><h1>应用已部署</h1><p>{data?.message||'正在连接 Spring Boot…'}</p><p>React 前端 / Spring Boot 后端 / 批准镜像与应用槽分配（共享演示不代表独占ECS）</p><pre>{JSON.stringify(data,null,2)}</pre></main>}
createRoot(document.getElementById('root')!).render(<App/>);
