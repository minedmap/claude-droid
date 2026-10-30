import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
// item 29: HOME-independent (set before anything calls os.homedir())
process.env.HOME = fs.mkdtempSync(path.join(process.env.TMPDIR || os.tmpdir(), 'cd-home-'));
import { createBridge, sanitizeName } from '../server.mjs';
import { fileURLToPath } from 'node:url';

const fake = fileURLToPath(new URL('./fake-claude.sh', import.meta.url));
let srv, base;
const H = { 'X-Token': 't0k', 'Content-Type': 'application/json' };
before(async () => {
  srv = createBridge({ token: 't0k', adbBin: fileURLToPath(new URL('./fake-adb.sh', import.meta.url)), claudeBin: fake });
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${srv.address().port}`;
});
after(() => srv.close());

function parseSse(text) {
  return text.split('\n\n').filter(Boolean).map(b => {
    const m = /^id: (\d+)\ndata: (.*)$/s.exec(b);
    assert.ok(m, 'bad SSE block: ' + b.slice(0, 80));
    return { id: Number(m[1]), data: JSON.parse(m[2]) };
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const stdinOf = ev => Buffer.from(ev.stdin_b64, 'base64').toString('utf8');
async function chat(body) {
  const r = await fetch(base + '/chat', { method: 'POST', headers: H, body: JSON.stringify(body) });
  const text = await r.text();
  return { r, events: parseSse(text).map(e => e.data) , raw: parseSse(text) };
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
test('empty model / null effort allowed and omitted', async () => {
  const { events } = await chat({ prompt: 'yo', model: '', effort: null });
  assert.doesNotMatch(events[0].args, /--effort|--model/);
});
test('invalid effort -> 400 JSON', async () => {
  const r = await fetch(base + '/chat', { method: 'POST', headers: H, body: JSON.stringify({ prompt: 'yo', effort: 'bogus' }) });
  assert.equal(r.status, 400);
  assert.deepEqual(await r.json(), { error: 'invalid effort' });
});
test('invalid model -> 400 JSON', async () => {
  const r = await fetch(base + '/chat', { method: 'POST', headers: H, body: JSON.stringify({ prompt: 'yo', model: 'bad model;rm' }) });
  assert.equal(r.status, 400);
  assert.deepEqual(await r.json(), { error: 'invalid model' });
});
test('prompt via stdin, not argv; leading dash ok', async () => {
  const { events } = await chat({ prompt: '--help secret-prompt' });
  assert.equal(stdinOf(events[0]), '--help secret-prompt');
  assert.doesNotMatch(events[0].args, /secret-prompt/);
  assert.match(events[0].args, /^-p /);
});
test('prompt > 128KB works', async () => {
  const big = '-' + 'x'.repeat(300 * 1024);
  const { events } = await chat({ prompt: big });
  assert.equal(stdinOf(events[0]), big);
  assert.equal(events.at(-1).code, 0);
});
test('SSE ids are 0-based indexes', async () => {
  const { raw } = await chat({ prompt: 'yo' });
  assert.deepEqual(raw.map(e => e.id), raw.map((_, i) => i));
});
test('stderr proot warning/info filtered; error result passes through', async () => {
  const { events } = await chat({ prompt: 'ERRRESULT' });
  const d = events.at(-1);
  assert.equal(d.code, 1);
  assert.equal(d.stderr.trim(), 'real error');
  assert.ok(events.some(e => e.is_error === true));
});
test('/health?x=1 works (pathname routing)', async () => {
  assert.equal((await fetch(base + '/health?x=1', { headers: H })).status, 200);
});
test('4xx errors are JSON {error}', async () => {
  const r = await fetch(base + '/nope', { headers: H });
  assert.equal(r.status, 404);
  assert.equal(typeof (await r.json()).error, 'string');
  const r2 = await fetch(base + '/health');
  assert.equal(r2.status, 401);
  assert.equal(typeof (await r2.json()).error, 'string');
});
test('disconnect does not kill; resume via GET /chat/:reqId?from=N; /runs lists', async () => {
  const ac = new AbortController();
  const r = await fetch(base + '/chat', { method: 'POST', headers: H, signal: ac.signal, body: JSON.stringify({ prompt: 'SLOWSHORT', reqId: 'rs1' }) });
  const reader = r.body.getReader();
  await reader.read();
  ac.abort();
  await sleep(100);
  const runs = (await (await fetch(base + '/runs', { headers: H })).json()).runs;
  const run = runs.find(x => x.reqId === 'rs1');
  assert.ok(run); assert.equal(run.done, false); assert.equal(run.code, null);
  const r2 = await fetch(base + '/chat/rs1?from=1', { headers: H });
  const ev = parseSse(await r2.text());
  assert.equal(ev[0].id, 1);
  assert.equal(ev.at(-1).data.type, 'done');
  assert.equal(ev.at(-1).data.code, 0);
  const after = (await (await fetch(base + '/runs', { headers: H })).json()).runs.find(x => x.reqId === 'rs1');
  assert.equal(after.done, true); assert.equal(after.code, 0); assert.equal(after.events, ev.at(-1).id + 1);
  // replay of finished run
  const r3 = parseSse(await (await fetch(base + '/chat/rs1', { headers: H })).text());
  assert.equal(r3[0].id, 0);
});
test('GET /chat/unknown -> 404 JSON', async () => {
  const r = await fetch(base + '/chat/nope', { headers: H });
  assert.equal(r.status, 404);
  assert.deepEqual(await r.json(), { error: 'unknown reqId' });
});
test('readJson body > 10MB -> 413', async () => {
  const r = await fetch(base + '/cancel', { method: 'POST', headers: H, body: JSON.stringify({ x: 'a'.repeat(10 * 1024 * 1024 + 10) }) }).catch(() => ({ status: 413 }));
  assert.equal(r.status, 413);
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
test('adb status parses devices', async () => {
  const r = await fetch(base + '/adb', { headers: H });
  const j = await r.json();
  assert.equal(j.connected, true);
  assert.deepEqual(j.devices[0], { serial: 'localhost:40123', state: 'device' });
  assert.equal(j.devices.length, 2);
});
test('404 unknown route', async () => {
  assert.equal((await fetch(base + '/nope', { headers: H })).status, 404);
});

// ---- upload / file / stat ----
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'cd-test-'));
const up = (name, body, cwd = work) => fetch(`${base}/upload?cwd=${encodeURIComponent(cwd)}&name=${encodeURIComponent(name)}`, { method: 'POST', headers: { 'X-Token': 't0k' }, body });

test('upload saves file under cwd/.claude-droid/uploads', async () => {
  const r = await up('a.txt', 'hello');
  assert.equal(r.status, 200);
  const { path: p } = await r.json();
  assert.equal(p, path.join(work, '.claude-droid', 'uploads', 'a.txt'));
  assert.equal(fs.readFileSync(p, 'utf8'), 'hello');
});
test('upload sanitizes name and avoids collision', async () => {
  const p1 = (await (await up('../../evil name!.sh', 'x')).json()).path;
  assert.equal(path.dirname(p1), path.join(work, '.claude-droid', 'uploads'));
  assert.match(path.basename(p1), /^[\p{L}\p{N} ._()-]+$/u);
  assert.equal(path.basename(p1), 'evil name_.sh');
  const a = (await (await up('dup.txt', '1')).json()).path;
  const b = (await (await up('dup.txt', '2')).json()).path;
  assert.equal(path.basename(a), 'dup.txt');
  assert.equal(path.basename(b), 'dup-1.txt');
  assert.equal(fs.readFileSync(a, 'utf8'), '1');
});
test('sanitizeName unicode and traversal', () => {
  assert.equal(sanitizeName('../../evil.png'), 'evil.png');
  assert.equal(sanitizeName('사진 (1).jpg'), '사진 (1).jpg');
  assert.equal(sanitizeName('a\\b\\c.txt'), 'c.txt');
  assert.equal(sanitizeName('x\u0000y\u0007.txt'), 'x_y_.txt');
  assert.equal(sanitizeName('...'), 'upload.bin');
});
test('upload nonexistent cwd -> 200 warning, saved to home', async () => {
  const r = await up(`nc-${process.pid}.txt`, 'z', '/no/such/dir-xyz');
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.warning, 'cwd not found, saved to home');
  assert.ok(j.path.startsWith(path.join(os.homedir(), '.claude-droid', 'uploads')));
});
test('upload aborted mid-body deletes partial file', async () => {
  const dir = path.join(work, 'abort'); fs.mkdirSync(dir);
  const { request } = await import('node:http');
  await new Promise(ok => {
    const rq = request(`${base}/upload?cwd=${encodeURIComponent(dir)}&name=part.bin`, { method: 'POST', headers: { 'X-Token': 't0k', 'Content-Length': '100000' } });
    rq.on('error', () => ok());
    rq.write('abc');
    setTimeout(() => { rq.destroy(); setTimeout(ok, 200); }, 150);
  });
  const up2 = path.join(dir, '.claude-droid', 'uploads');
  assert.deepEqual(fs.existsSync(up2) ? fs.readdirSync(up2) : [], []);
});
test('upload empty/odd name falls back to upload.bin', async () => {
  const p = (await (await up('///', 'x')).json()).path;
  assert.equal(path.basename(p), 'upload.bin');
});
test('upload without cwd goes to HOME', async () => {
  const r = await fetch(`${base}/upload?name=home-${process.pid}.txt`, { method: 'POST', headers: { 'X-Token': 't0k' }, body: 'h' });
  const { path: p } = await r.json();
  assert.ok(p.startsWith(path.join(os.homedir(), '.claude-droid', 'uploads')));
  fs.rmSync(p);
});
test('upload > 50MB -> 413', async () => {
  const r = await up('big.bin', Buffer.alloc(50 * 1024 * 1024 + 1)).catch(e => ({ status: 413 }));
  assert.equal(r.status, 413);
});
test('upload needs token', async () => {
  const r = await fetch(`${base}/upload?name=a`, { method: 'POST', body: 'x' });
  assert.equal(r.status, 401);
});

const getF = (p, ep = 'file') => fetch(`${base}/${ep}?path=${encodeURIComponent(p)}`, { headers: H });
test('file serves content with type and length', async () => {
  const f = path.join(work, 'x.png'); fs.writeFileSync(f, Buffer.from([1, 2, 3, 4]));
  const r = await getF(f);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('content-type'), 'image/png');
  assert.equal(r.headers.get('content-length'), '4');
  assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [1, 2, 3, 4]);
});
test('file content types by extension', async () => {
  const want = { 'a.md': 'text/markdown', 'a.json': 'application/json', 'a.txt': 'text/plain', 'a.csv': 'text/csv', 'a.pdf': 'application/pdf', 'a.svg': 'image/svg+xml', 'a.jpg': 'image/jpeg', 'a.webp': 'image/webp', 'a.zzz': 'application/octet-stream' };
  for (const [n, t] of Object.entries(want)) {
    const f = path.join(work, n); fs.writeFileSync(f, 'x');
    const r = await getF(f); await r.arrayBuffer();
    assert.match(r.headers.get('content-type'), new RegExp('^' + t.replace(/[+/]/g, '\\$&')), n);
  }
});
test('file 404 missing', async () => {
  assert.equal((await getF(path.join(work, 'nope.txt'))).status, 404);
});
test('file 403 outside allowed roots and traversal', async () => {
  assert.equal((await getF('/etc/passwd')).status, 403);
  assert.equal((await getF(path.join(work, ...Array(12).fill('..'), 'etc', 'passwd'))).status, 403);
  const link = path.join(work, 'lnk'); try { fs.symlinkSync('/etc/passwd', link); } catch {}
  assert.equal((await getF(link)).status, 403);
});
test('file relative path -> 400', async () => {
  assert.equal((await getF('rel/x.txt')).status, 400);
});
test('file > 50MB -> 413', async () => {
  const f = path.join(work, 'huge.bin'); fs.closeSync(fs.openSync(f, 'w')); fs.truncateSync(f, 50 * 1024 * 1024 + 1);
  assert.equal((await getF(f)).status, 413);
  fs.rmSync(f);
});
test('deny-list -> 403', async () => {
  const names = ['.credentials.json', '.claude-droid-token', '.ssh/id_rsa', '.android/adbkey', '.android/adbkey.pub', 'k.pem', 'k.key', '.env', '.git-credentials', '.netrc'];
  for (const n of names) {
    const f = path.join(work, n); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, 'secret');
    assert.equal((await getF(f)).status, 403, n);
    assert.equal((await getF(f, 'stat')).status, 403, n);
  }
  const ok = path.join(work, 'fine.txt'); fs.writeFileSync(ok, 'x');
  assert.equal((await getF(ok)).status, 200);
});
test('stat reports exists/size/isDir', async () => {
  const f = path.join(work, 's.txt'); fs.writeFileSync(f, 'abc');
  assert.deepEqual(await (await getF(f, 'stat')).json(), { exists: true, size: 3, isDir: false });
  assert.deepEqual(await (await getF(work, 'stat')).json(), { exists: true, size: fs.statSync(work).size, isDir: true });
  assert.deepEqual(await (await getF(path.join(work, 'none'), 'stat')).json(), { exists: false, size: 0, isDir: false });
  assert.equal((await getF('/etc/passwd', 'stat')).status, 403);
});
test('/root/ maps to UBUNTU_ROOTFS', async () => {
  const rootfs = path.join(work, 'rootfs'); fs.mkdirSync(path.join(rootfs, 'root'), { recursive: true });
  fs.writeFileSync(path.join(rootfs, 'root', 'r.md'), '# hi');
  process.env.UBUNTU_ROOTFS = rootfs;
  try {
    const r = await getF('/root/r.md');
    assert.equal(r.status, 200);
    assert.equal(await r.text(), '# hi');
    assert.equal((await getF('/root/../../etc/passwd')).status, 403);
  } finally { delete process.env.UBUNTU_ROOTFS; }
});
test('rootfs autodetect: containers/ubuntu/rootfs preferred over installed-rootfs', async () => {
  const pre = path.join(work, 'prefix');
  const c = path.join(pre, 'var/lib/proot-distro/containers/ubuntu/rootfs');
  const legacy = path.join(pre, 'var/lib/proot-distro/installed-rootfs/ubuntu');
  fs.mkdirSync(path.join(c, 'root'), { recursive: true }); fs.mkdirSync(path.join(legacy, 'root'), { recursive: true });
  fs.writeFileSync(path.join(c, 'root', 'a.png'), 'new'); fs.writeFileSync(path.join(legacy, 'root', 'a.png'), 'old');
  const oldP = process.env.PREFIX; process.env.PREFIX = pre;
  try {
    assert.equal(await (await getF('/root/a.png')).text(), 'new');
    fs.rmSync(path.join(pre, 'var/lib/proot-distro/containers'), { recursive: true });
    assert.equal(await (await getF('/root/a.png')).text(), 'old');
  } finally { if (oldP === undefined) delete process.env.PREFIX; else process.env.PREFIX = oldP; }
});
test('non-/root guest paths (/tmp, /opt) fall back to rootfs when missing on host', async () => {
  const rootfs = path.join(work, 'rootfs2');
  fs.mkdirSync(path.join(rootfs, 'opt/x'), { recursive: true });
  fs.writeFileSync(path.join(rootfs, 'opt/x', 'screen-cd-test.png'), 'PNG');
  process.env.UBUNTU_ROOTFS = rootfs;
  try {
    const r = await getF('/opt/x/screen-cd-test.png');
    assert.equal(r.status, 200); assert.equal(await r.text(), 'PNG');
    assert.equal((await getF('/etc/passwd')).status, 403); // exists on host -> not remapped
  } finally { delete process.env.UBUNTU_ROOTFS; }
});
test('upload into /root cwd lands in rootfs and returns guest path', async () => {
  const rootfs = path.join(work, 'rootfs3'); fs.mkdirSync(path.join(rootfs, 'root/proj'), { recursive: true });
  process.env.UBUNTU_ROOTFS = rootfs;
  try {
    const r = await fetch(`${base}/upload?cwd=/root/proj&name=u.txt`, { method: 'POST', headers: { 'X-Token': 't0k' }, body: 'hi' });
    const { path: p } = await r.json();
    assert.equal(p, '/root/proj/.claude-droid/uploads/u.txt');
    assert.equal(fs.readFileSync(path.join(rootfs, p), 'utf8'), 'hi');
  } finally { delete process.env.UBUNTU_ROOTFS; }
});
after(() => fs.rmSync(work, { recursive: true, force: true }));
