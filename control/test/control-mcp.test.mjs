import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createControlMcp, TOOLS, parseDevices, parseUiTree, pngSize } from '../control-mcp.mjs';

const script = fileURLToPath(new URL('../control-mcp.mjs', import.meta.url));
const fakeAdb = fileURLToPath(new URL('./fake-adb.sh', import.meta.url));
const rpc = (method, params, id = 1) => ({ jsonrpc: '2.0', id, method, params });

function png(w, h) {
  const b = Buffer.alloc(32);
  b.writeUInt32BE(0x89504e47, 0); b.writeUInt32BE(0x0d0a1a0a, 4);
  b.writeUInt32BE(w, 16); b.writeUInt32BE(h, 20);
  return b;
}
const XML = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" content-desc="" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
<node index="0" text="Tom &amp; Jerry" resource-id="com.x:id/title" class="android.widget.TextView" content-desc="" clickable="false" enabled="true" bounds="[100,200][500,300]" />
<node index="1" text="" resource-id="com.x:id/send" class="android.widget.ImageButton" content-desc="Send" clickable="true" enabled="false" bounds="[900,2200][1080,2400]" />
</node></hierarchy>
UI hierchary dumped to: /dev/tty`;

/** Fake exec: records every call; `over` can replace the reply for a device shell command or a binary. */
function fake({ devices = 'List of devices attached\nlocalhost:40123\tdevice\n\n', magick = false, over = {} } = {}) {
  const calls = [];
  const exec = async (bin, args, opts = {}) => {
    calls.push({ bin, args, opts });
    const out = s => ({ stdout: Buffer.isBuffer(s) ? s : Buffer.from(s), stderr: '' });
    if (bin === 'magick') { if (!magick) throw Object.assign(new Error('nope'), { code: 'ENOENT' }); return out(args[0] === '-version' ? 'Version: ImageMagick 7' : 'JPEGDATA'); }
    if (bin === 'convert') throw Object.assign(new Error('nope'), { code: 'ENOENT' });
    if (args[0] === 'devices') return out(devices);
    const rest = args.slice(2);   // after -s <serial>
    if (rest[0] === 'exec-out' && rest[1] === 'screencap') return out(png(1080, 2400));
    if (rest[0] === 'exec-out' && rest[1] === 'uiautomator') return out(over.uiautomator ?? XML);
    if (rest[0] === 'shell') {
      const cmd = rest[1];
      for (const [k, v] of Object.entries(over.shell || {})) if (cmd.startsWith(k)) { if (v instanceof Error) throw v; return out(v); }
      if (cmd === 'wm size') return out('Physical size: 1080x2400\n');
      if (cmd.startsWith('pm list packages')) return out('package:org.b\npackage:com.a\n');
      if (cmd.startsWith('getprop')) return out('samsung\nSM-S911N\n14\n34\n  mCurrentFocus=Window{abc u0 com.a/com.a.Main}\n');
      return out('');
    }
    throw new Error('unexpected: ' + bin + ' ' + args.join(' '));
  };
  return { exec, calls, shells: () => calls.filter(c => c.args[2] === 'shell').map(c => c.args[3]) };
}
const mk = (f, env = {}) => createControlMcp({ exec: f.exec, env });
const text = r => JSON.parse(r.result.content.find(c => c.type === 'text').text);

test('initialize + tools/list', async () => {
  const m = mk(fake());
  const i = await m.handle(rpc('initialize', { protocolVersion: '2025-03-26' }));
  assert.equal(i.result.protocolVersion, '2025-03-26');
  assert.equal(i.result.serverInfo.name, 'droid-control');
  const l = await m.handle(rpc('tools/list'));
  assert.deepEqual(l.result.tools.map(t => t.name).sort(),
    ['device_info', 'key', 'list_apps', 'long_press', 'open_app', 'screenshot', 'shell', 'swipe', 'tap', 'type', 'ui_tree']);
  for (const t of l.result.tools) { assert.equal(t.inputSchema.type, 'object'); assert.ok(t.description); }
  assert.equal(Object.keys(TOOLS).length, 11);
});

test('notifications get no response; unknown method and tool are errors', async () => {
  const m = mk(fake());
  assert.equal(await m.handle({ jsonrpc: '2.0', method: 'notifications/initialized' }), null);
  assert.equal((await m.handle(rpc('nope'))).error.code, -32601);
  assert.equal((await m.handle(rpc('tools/call', { name: 'nope' }))).error.code, -32602);
});

test('tap without ImageMagick uses device pixels and the detected serial', async () => {
  const f = fake(); const m = mk(f);
  const r = await m.handle(rpc('tools/call', { name: 'tap', arguments: { x: 540, y: 1200 } }));
  assert.equal(r.result.isError, undefined);
  const c = f.calls.find(c => c.args[3] === 'input tap 540 1200');
  assert.deepEqual(c.args.slice(0, 3), ['-s', 'localhost:40123', 'shell']);
});

test('with ImageMagick: screenshot is downsized and coordinates are scaled back', async () => {
  const f = fake({ magick: true }); const m = mk(f);
  const r = await m.handle(rpc('tools/call', { name: 'screenshot', arguments: {} }));
  const img = r.result.content.find(c => c.type === 'image');
  assert.equal(img.mimeType, 'image/jpeg');
  assert.equal(Buffer.from(img.data, 'base64').toString(), 'JPEGDATA');
  assert.deepEqual(text(r), { width: 576, height: 1280, deviceWidth: 1080, deviceHeight: 2400, scale: 1.875 });
  const resize = f.calls.find(c => c.bin === 'magick' && c.args[0] === 'png:-');
  assert.ok(resize.args.includes('1280x1280>')); assert.ok(Buffer.isBuffer(resize.opts.input));
  await m.call('tap', { x: 288, y: 640 });
  await m.call('swipe', { x1: 100, y1: 1000, x2: 100, y2: 200 });
  await m.call('long_press', { x: 16, y: 16, durationMs: 500 });
  assert.deepEqual(f.shells().filter(s => s.startsWith('input')),
    ['input tap 540 1200', 'input swipe 188 1875 188 375 300', 'input swipe 30 30 30 30 500']);
  const t = await m.call('ui_tree', {});
  assert.deepEqual(t.nodes[0].bounds, [53, 107, 267, 160]);
});

test('screenshot without ImageMagick returns the raw PNG at scale 1; CONTROL_MAX_SIDE=0 never probes', async () => {
  const r = await mk(fake()).handle(rpc('tools/call', { name: 'screenshot', arguments: {} }));
  assert.equal(r.result.content[0].mimeType, 'image/png');
  assert.equal(text(r).scale, 1); assert.equal(text(r).width, 1080);
  const f = fake({ magick: true });
  await mk(f, { CONTROL_MAX_SIDE: '0' }).call('screenshot', {});
  assert.equal(f.calls.some(c => c.bin === 'magick'), false);
});

test('type: quoting, spaces, newlines, enter and the ASCII limit', async () => {
  const f = fake(); const m = mk(f);
  await m.call('type', { text: "it's a test\nline2", enter: true });
  assert.deepEqual(f.shells(), [`input text 'it'\\''s%sa%stest'`, 'input keyevent 66', `input text 'line2'`, 'input keyevent 66']);
  const r = await m.handle(rpc('tools/call', { name: 'type', arguments: { text: '안녕' } }));
  assert.equal(r.result.isError, true); assert.match(r.result.content[0].text, /ASCII/);
});

test('key: names, numbers, KEYCODE_ names, rejects junk', async () => {
  const f = fake(); const m = mk(f);
  assert.equal((await m.call('key', { key: 'Home' })).keycode, 3);
  await m.call('key', { key: '66' }); await m.call('key', { key: 'KEYCODE_CAMERA' });
  assert.deepEqual(f.shells(), ['input keyevent 3', 'input keyevent 66', 'input keyevent KEYCODE_CAMERA']);
  await assert.rejects(m.call('key', { key: 'home; rm -rf /' }), /unknown key/);
});

test('ui_tree: filters, decodes entities, falls back to a file dump', async () => {
  const m = mk(fake());
  const t = await m.call('ui_tree', {});
  assert.equal(t.count, 2);
  assert.deepEqual(t.nodes[0], { bounds: [100, 200, 500, 300], center: [300, 250], text: 'Tom & Jerry', id: 'title', class: 'TextView' });
  assert.deepEqual(t.nodes[1], { bounds: [900, 2200, 1080, 2400], center: [990, 2300], desc: 'Send', id: 'send', class: 'ImageButton', flags: ['clickable', 'disabled'] });
  assert.equal((await m.call('ui_tree', { all: true })).count, 3);
  const f = fake({ over: { uiautomator: 'Killed', shell: { 'uiautomator dump': XML } } });
  assert.equal((await mk(f).call('ui_tree', {})).count, 2);
  assert.equal(parseUiTree('<node text="x" />').length, 0);   // no bounds -> skipped
});

test('open_app / list_apps / device_info', async () => {
  const f = fake(); const m = mk(f);
  await m.call('open_app', { package: 'com.a' });
  await m.call('open_app', { package: 'com.a', activity: '.Main' });
  assert.deepEqual(f.shells(), ['monkey -p com.a -c android.intent.category.LAUNCHER 1', 'am start -n com.a/.Main']);
  await assert.rejects(m.call('open_app', { package: 'com.a; reboot' }), /invalid package/);
  assert.deepEqual(await m.call('list_apps', {}), { count: 2, packages: ['com.a', 'org.b'] });
  assert.deepEqual((await m.call('list_apps', { filter: 'ORG' })).packages, ['org.b']);
  assert.ok(f.shells().includes('pm list packages -3'));
  const d = await m.call('device_info', {});
  assert.deepEqual(d, { serial: 'localhost:40123', manufacturer: 'samsung', model: 'SM-S911N', android: '14', sdk: 34,
    screen: { width: 1080, height: 2400 }, scale: 1, focus: 'com.a/com.a.Main' });
});

test('shell returns output and non-zero exit codes instead of failing', async () => {
  const err = Object.assign(new Error('Command failed'), { code: 2, stdout: Buffer.from('partial'), stderr: 'ls: nope' });
  const m = mk(fake({ over: { shell: { 'echo hi': 'hi\n', 'ls /nope': err } } }));
  assert.deepEqual(await m.call('shell', { command: 'echo hi' }), { exitCode: 0, output: 'hi\n' });
  assert.deepEqual(await m.call('shell', { command: 'ls /nope' }), { exitCode: 2, output: 'partialls: nope' });
});

test('errors: missing args, bad coordinates, no device, ADB_SERIAL, adb missing', async () => {
  const m = mk(fake());
  await assert.rejects(m.call('tap', { x: 1 }), /missing argument: y/);
  await assert.rejects(m.call('tap', { x: 'a', y: 1 }), /invalid x/);
  const none = mk(fake({ devices: 'List of devices attached\nlocalhost:1\toffline\n' }));
  const r = await none.handle(rpc('tools/call', { name: 'tap', arguments: { x: 1, y: 1 } }));
  assert.equal(r.result.isError, true);
  assert.match(r.result.content[0].text, /no adb device connected \(seen: localhost:1 offline\)/);
  const f = fake(); await mk(f, { ADB_SERIAL: 'emu-1' }).call('key', { key: 'back' });
  assert.equal(f.calls.some(c => c.args[0] === 'devices'), false);
  assert.deepEqual(f.calls.at(-1).args, ['-s', 'emu-1', 'shell', 'input keyevent 4']);
  const gone = createControlMcp({ exec: async () => { throw Object.assign(new Error('spawn adb ENOENT'), { code: 'ENOENT' }); }, env: {} });
  await assert.rejects(gone.call('tap', { x: 1, y: 1 }), /adb not found/);
});

test('helpers', () => {
  assert.deepEqual(parseDevices('* daemon started successfully\nList of devices attached\na\tdevice\nb\tunauthorized\n\n'),
    [{ serial: 'a', state: 'device' }, { serial: 'b', state: 'unauthorized' }]);
  assert.deepEqual(pngSize(png(3, 4)), { width: 3, height: 4 });
  assert.equal(pngSize(Buffer.from('nope')), null);
});

test('stdio: real process against a fake adb binary', async () => {
  const p = spawn(process.execPath, [script], { env: { ...process.env, ADB_BIN: fakeAdb, CONTROL_MAX_SIDE: '0' }, stdio: ['pipe', 'pipe', 'inherit'] });
  let out = ''; p.stdout.on('data', d => out += d);
  p.stdin.write(JSON.stringify(rpc('initialize', {}, 1)) + '\n');
  p.stdin.write('not json\n');
  p.stdin.write(JSON.stringify(rpc('tools/call', { name: 'key', arguments: { key: 'home' } }, 2)) + '\n');
  p.stdin.write(JSON.stringify(rpc('tools/call', { name: 'shell', arguments: { command: 'exit 3' } }, 3)) + '\n');
  p.stdin.end();
  const code = await new Promise(r => p.on('close', r));
  assert.equal(code, 0);
  const msgs = out.trim().split('\n').map(l => JSON.parse(l));
  assert.equal(msgs.length, 4);
  const by = id => msgs.find(m => m.id === id);
  assert.equal(by(1).result.serverInfo.name, 'droid-control');
  assert.equal(by(null).error.code, -32700);
  assert.deepEqual(JSON.parse(by(2).result.content[0].text), { ok: true, keycode: 3 });
  assert.deepEqual(JSON.parse(by(3).result.content[0].text), { exitCode: 3, output: 'fake: exit 3\n' });
});
