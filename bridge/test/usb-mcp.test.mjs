import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createUsbMcp, TOOLS, loadToken } from '../usb-mcp.mjs';

const script = fileURLToPath(new URL('../usb-mcp.mjs', import.meta.url));
let srv, url; const seen = [];
before(async () => {
  srv = http.createServer((q, s) => {
    let b = ''; q.on('data', c => b += c);
    q.on('end', () => {
      seen.push({ method: q.method, url: q.url, token: q.headers['x-token'], body: b ? JSON.parse(b) : null });
      s.setHeader('content-type', 'application/json');
      if (q.headers['x-token'] !== 'tok') { s.statusCode = 401; return s.end('{"error":"bad token"}'); }
      if (q.url.startsWith('/usb/devices')) return s.end(JSON.stringify({ devices: [{ id: '/dev/bus/usb/001/002', vidpid: '2341:0043' }] }));
      if (q.url.startsWith('/serial/read')) return s.end('{"text":"hi","hex":"6869","bytes":2}');
      if (q.url === '/usb/permission') { s.statusCode = 404; return s.end('{"error":"device not found: x"}'); }
      s.end(JSON.stringify({ ok: true, echo: b ? JSON.parse(b) : null }));
    });
  });
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  url = `http://127.0.0.1:${srv.address().port}`;
});
after(() => srv.close());

const rpc = (method, params, id = 1) => ({ jsonrpc: '2.0', id, method, params });

test('initialize + tools/list covers all 12 tools', async () => {
  const m = createUsbMcp({ url, token: 'tok' });
  const i = await m.handle(rpc('initialize', { protocolVersion: '2025-03-26' }));
  assert.equal(i.result.protocolVersion, '2025-03-26');
  assert.ok(i.result.capabilities.tools);
  const l = await m.handle(rpc('tools/list'));
  const names = l.result.tools.map(t => t.name).sort();
  assert.deepEqual(names, ['avr_flash', 'serial_close', 'serial_lines', 'serial_open', 'serial_read', 'serial_tcp', 'serial_write',
    'usb_control', 'usb_descriptors', 'usb_list', 'usb_permission', 'usb_transfer'].sort());
  for (const t of l.result.tools) { assert.equal(t.inputSchema.type, 'object'); assert.ok(t.description); }
});

test('notifications get no response', async () => {
  const m = createUsbMcp({ url, token: 'tok' });
  assert.equal(await m.handle({ jsonrpc: '2.0', method: 'notifications/initialized' }), null);
});

test('tools/call usb_list sends X-Token and returns text JSON', async () => {
  const m = createUsbMcp({ url, token: 'tok' });
  const r = await m.handle(rpc('tools/call', { name: 'usb_list', arguments: {} }));
  assert.ok(!r.result.isError);
  assert.equal(JSON.parse(r.result.content[0].text).devices[0].vidpid, '2341:0043');
  const last = seen.at(-1);
  assert.equal(last.token, 'tok'); assert.equal(last.method, 'GET'); assert.equal(last.url, '/usb/devices');
});

test('GET tools put args in the query string', async () => {
  const m = createUsbMcp({ url, token: 'tok' });
  await m.handle(rpc('tools/call', { name: 'serial_read', arguments: { device: '/dev/bus/usb/001/002', max: 10, waitMs: 5 } }));
  const u = new URL('http://x' + seen.at(-1).url);
  assert.equal(u.pathname, '/serial/read');
  assert.equal(u.searchParams.get('device'), '/dev/bus/usb/001/002'); assert.equal(u.searchParams.get('max'), '10');
});

test('POST tools send JSON body', async () => {
  const m = createUsbMcp({ url, token: 'tok' });
  await m.handle(rpc('tools/call', { name: 'serial_open', arguments: { device: 'd', baud: 9600 } }));
  assert.deepEqual(seen.at(-1).body, { device: 'd', baud: 9600 });
});

test('avr_flash reads hexPath and sends hex text', async () => {
  const f = path.join(fs.mkdtempSync(path.join(process.env.TMPDIR || os.tmpdir(), 'hx-')), 'a.hex');
  fs.writeFileSync(f, ':00000001FF\n');
  const m = createUsbMcp({ url, token: 'tok' });
  const r = await m.handle(rpc('tools/call', { name: 'avr_flash', arguments: { device: 'd', board: 'uno', hexPath: f } }));
  assert.ok(!r.result.isError);
  assert.deepEqual(seen.at(-1).body, { device: 'd', board: 'uno', hex: ':00000001FF\n' });
});

test('avr_flash errors: missing hex, unreadable path', async () => {
  const m = createUsbMcp({ url, token: 'tok' });
  const a = await m.handle(rpc('tools/call', { name: 'avr_flash', arguments: { device: 'd' } }));
  assert.equal(a.result.isError, true); assert.match(a.result.content[0].text, /hexPath or hex/);
  const b = await m.handle(rpc('tools/call', { name: 'avr_flash', arguments: { device: 'd', hexPath: '/nonexistent/x.hex' } }));
  assert.equal(b.result.isError, true); assert.match(b.result.content[0].text, /cannot read hexPath/);
});

test('error paths: bad token, app error, missing arg, unknown tool, unknown method', async () => {
  const bad = createUsbMcp({ url, token: 'nope' });
  const r1 = await bad.handle(rpc('tools/call', { name: 'usb_list', arguments: {} }));
  assert.equal(r1.result.isError, true); assert.match(r1.result.content[0].text, /401/);
  const m = createUsbMcp({ url, token: 'tok' });
  const r2 = await m.handle(rpc('tools/call', { name: 'usb_permission', arguments: { device: 'x' } }));
  assert.equal(r2.result.isError, true); assert.match(r2.result.content[0].text, /device not found/);
  const r3 = await m.handle(rpc('tools/call', { name: 'serial_write', arguments: {} }));
  assert.match(r3.result.content[0].text, /missing argument: device/);
  const r4 = await m.handle(rpc('tools/call', { name: 'nope', arguments: {} }));
  assert.equal(r4.error.code, -32602);
  const r5 = await m.handle(rpc('bogus'));
  assert.equal(r5.error.code, -32601);
});

test('app down -> clear Korean error', async () => {
  const m = createUsbMcp({ url: 'http://127.0.0.1:1', token: 'tok' });
  const r = await m.handle(rpc('tools/call', { name: 'usb_list', arguments: {} }));
  assert.equal(r.result.isError, true); assert.match(r.result.content[0].text, /USB 서비스가 꺼져 있음/);
});

test('no token -> clear error', async () => {
  const m = createUsbMcp({ url, token: '' });
  const r = await m.handle(rpc('tools/call', { name: 'usb_list', arguments: {} }));
  assert.match(r.result.content[0].text, /no token/);
});

test('loadToken prefers env', () => { assert.equal(loadToken({ CLAUDE_DROID_TOKEN: ' abc ' }), 'abc'); });

test('stdio round trip through the real process', async () => {
  const p = spawn(process.execPath, [script], { env: { ...process.env, USB_BRIDGE_URL: url, CLAUDE_DROID_TOKEN: 'tok' }, stdio: ['pipe', 'pipe', 'inherit'] });
  const lines = [];
  let buf = '';
  p.stdout.on('data', d => { buf += d; let i; while ((i = buf.indexOf('\n')) >= 0) { lines.push(JSON.parse(buf.slice(0, i))); buf = buf.slice(i + 1); } });
  p.stdin.write(JSON.stringify(rpc('initialize', {}, 1)) + '\n');
  p.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  p.stdin.write(JSON.stringify(rpc('tools/call', { name: 'usb_list', arguments: {} }, 2)) + '\n');
  p.stdin.end();
  await new Promise(r => p.on('close', r));
  assert.deepEqual(lines.map(l => l.id).sort(), [1, 2]);
  assert.equal(JSON.parse(lines.find(l => l.id === 2).result.content[0].text).devices.length, 1);
});
