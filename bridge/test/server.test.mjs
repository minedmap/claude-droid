import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createBridge } from '../server.mjs';
import { fileURLToPath } from 'node:url';

const fake = fileURLToPath(new URL('./fake-claude.sh', import.meta.url));
let srv, base;
const H = { 'X-Token': 't0k', 'Content-Type': 'application/json' };
before(async () => {
  srv = createBridge({ token: 't0k', claudeBin: fake });
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${srv.address().port}`;
});
after(() => srv.close());

async function chat(body) {
  const r = await fetch(base + '/chat', { method: 'POST', headers: H, body: JSON.stringify(body) });
  const text = await r.text();
  return { r, events: text.split('\n\n').filter(Boolean).map(b => JSON.parse(b.replace(/^data: /, ''))) };
}

test('401 without token', async () => {
  assert.equal((await fetch(base + '/health')).status, 401);
});
test('health ok', async () => {
  const r = await fetch(base + '/health', { headers: H });
  assert.deepEqual(await r.json(), { ok: true });
});
test('chat streams SSE, passes --resume, ends with done', async () => {
  const { r, events } = await chat({ prompt: 'yo', sessionId: 'abc', cwd: process.cwd() });
  assert.equal(r.headers.get('content-type'), 'text/event-stream');
  assert.equal(events[0].session_id, 'abc');
  assert.equal(events.at(-1).type, 'done');
  assert.equal(events.at(-1).code, 0);
});
test('TMPDIR always set (claude-code#15637)', async () => {
  const { events } = await chat({ prompt: 'yo' });
  assert.ok(events[0].tmp.length > 0);
});
test('model/effort passed as flags', async () => {
  const { events } = await chat({ prompt: 'yo', model: 'opus', effort: 'high' });
  assert.match(events[0].args, /--model opus/);
  assert.match(events[0].args, /--effort high/);
});
test('invalid effort ignored', async () => {
  const { events } = await chat({ prompt: 'yo', model: '', effort: 'bogus' });
  assert.doesNotMatch(events[0].args, /--effort|--model/);
});
test('bad JSON -> 400, server survives', async () => {
  const r = await fetch(base + '/chat', { method: 'POST', headers: H, body: '{bad' });
  assert.equal(r.status, 400);
  assert.equal((await fetch(base + '/health', { headers: H })).status, 200);
});
test('partial messages flag on', async () => {
  const { events } = await chat({ prompt: 'yo' });
  assert.match(events[0].args, /--include-partial-messages/);
});
test('cancel kills whole process group fast', async () => {
  const t0 = Date.now();
  const p = chat({ prompt: 'SLOWCHILD', reqId: 'r1' });
  await new Promise(r => setTimeout(r, 400));
  await fetch(base + '/cancel', { method: 'POST', headers: H, body: JSON.stringify({ reqId: 'r1' }) });
  const { events } = await p;
  assert.equal(events.at(-1).type, 'done');
  assert.ok(Date.now() - t0 < 5000);
});
test('404 unknown route', async () => {
  assert.equal((await fetch(base + '/nope', { headers: H })).status, 404);
});
