import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {transform} from 'esbuild';

const source = await readFile(new URL('../src/request.ts', import.meta.url), 'utf8');
const {code} = await transform(source, {loader:'ts', format:'esm'});
const {requestJson} = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'));

test('connection that never replies fails within the configured deadline', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = (_url, {signal}) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
  });
  try { await assert.rejects(requestJson('/auth/login', {}, 20), /请求超时/); }
  finally { globalThis.fetch = original; }
});

test('deadline also covers a stalled response body after headers arrive', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async (_url, {signal}) => new Response(new ReadableStream({
    start(controller) { signal.addEventListener('abort', () => controller.error(new DOMException('aborted', 'AbortError'))); }
  }), {headers:{'Content-Type':'application/json'}});
  try { await assert.rejects(requestJson('/auth/login', {}, 20), /请求超时/); }
  finally { globalThis.fetch = original; }
});

test('HTTP errors retain the response and structured server message', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({message:'invalid credentials'}), {status:401});
  try {
    const result = await requestJson('/auth/login', {}, 100);
    assert.equal(result.response.status, 401);
    assert.equal(result.data.message, 'invalid credentials');
  } finally { globalThis.fetch = original; }
});
