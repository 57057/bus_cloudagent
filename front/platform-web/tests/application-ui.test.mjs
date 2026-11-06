import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {transform} from 'esbuild';
const {code}=await transform(await readFile(new URL('../src/application-ui.ts',import.meta.url),'utf8'),{loader:'ts',format:'esm'});
const {safeApplicationUrl,taskLabel,stageFor}=await import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'));
test('preview accepts only web URLs without embedded credentials',()=>{
 for(const url of ['javascript:alert(1)','data:text/html,hi','https://user:pass@example.com','//example.com',''])assert.equal(safeApplicationUrl(url),'');
 assert.equal(safeApplicationUrl('http://121.43.82.247:18080'),'http://121.43.82.247:18080/');
});
test('requested cancellation stays pending until runner acknowledges cleanup',()=>{
 assert.match(taskLabel({state:'RUNNING',cancel_requested:true}),/等待清理/);
 assert.equal(taskLabel({state:'CANCELLED',cancel_requested:true}),'已取消');
 assert.match(taskLabel({state:'RUNNING',commit_started:true}),/切换/);
 assert.equal(stageFor({state:'QUEUED'},[]),0);
 assert.equal(stageFor({state:'SUCCEEDED'},[]),4);
});
