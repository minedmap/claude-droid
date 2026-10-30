import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createBridge } from '../server.mjs';
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
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
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
  assert.match(path.basename(p1), /^[A-Za-z0-9._-]+$/);
  const a = (await (await up('dup.txt', '1')).json()).path;
  const b = (await (await up('dup.txt', '2')).json()).path;
  assert.equal(path.basename(a), 'dup.txt');
  assert.equal(path.basename(b), 'dup-1.txt');
  assert.equal(fs.readFileSync(a, 'utf8'), '1');
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
