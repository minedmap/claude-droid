#!/usr/bin/env node
// Droid Control MCP server (stdio, JSON-RPC 2.0, zero dependencies).
// "Computer use" for an Android device over adb. Works with any MCP client; needs no Claude Droid bridge or token.
//   ADB_BIN            adb binary (default: adb)
//   ADB_SERIAL         device serial (default: first device in `adb devices`)
//   CONTROL_MAX_SIDE   longest screenshot side in px when ImageMagick is available (default 1280, 0 = never resize)
import { execFile } from 'node:child_process';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';

const num = description => ({ type: 'number', description });
const obj = (properties, required = []) => ({ type: 'object', properties, required });
const XY = 'in screenshot pixels (the coordinate space of `screenshot` and `ui_tree`)';

export const KEYS = {
  back: 4, home: 3, recents: 187, enter: 66, backspace: 67, delete: 112, tab: 61, space: 62, escape: 111, menu: 82,
  search: 84, power: 26, wakeup: 224, sleep: 223, volume_up: 24, volume_down: 25, up: 19, down: 20, left: 21, right: 22,
};

export const TOOLS = {
  screenshot: { description: 'Capture the phone screen. Returns the image plus its size and the scale to device pixels. All coordinates used by the other tools are in this image\'s pixel space.',
    schema: obj({}) },
  tap: { description: 'Tap a point.', schema: obj({ x: num(`x ${XY}`), y: num(`y ${XY}`) }, ['x', 'y']) },
  long_press: { description: 'Press and hold a point.',
    schema: obj({ x: num(`x ${XY}`), y: num(`y ${XY}`), durationMs: num('hold time, default 800') }, ['x', 'y']) },
  swipe: { description: 'Swipe/drag from one point to another (scroll down = swipe up).',
    schema: obj({ x1: num(`start x ${XY}`), y1: num(`start y ${XY}`), x2: num('end x'), y2: num('end y'), durationMs: num('default 300') }, ['x1', 'y1', 'x2', 'y2']) },
  type: { description: 'Type text into the focused field. ASCII only (adb `input text` limitation). Newlines are sent as Enter.',
    schema: obj({ text: { type: 'string' }, enter: { type: 'boolean', description: 'press Enter afterwards' } }, ['text']) },
  key: { description: `Press a key: ${Object.keys(KEYS).join(', ')}, a numeric Android keycode, or a KEYCODE_* name.`,
    schema: obj({ key: { type: 'string' } }, ['key']) },
  ui_tree: { description: 'Dump the current screen as a compact list of UI elements (text, desc, id, class, bounds, center, flags). Prefer this over a screenshot for finding tap targets.',
    schema: obj({ all: { type: 'boolean', description: 'include elements with no text/desc that are not clickable or scrollable' } }) },
  open_app: { description: 'Launch an app by package name (optionally a specific activity).',
    schema: obj({ package: { type: 'string' }, activity: { type: 'string' } }, ['package']) },
  list_apps: { description: 'List installed packages (user-installed by default).',
    schema: obj({ system: { type: 'boolean', description: 'include system packages' }, filter: { type: 'string', description: 'substring filter' } }) },
  shell: { description: 'Run a shell command on the device as the adb shell user (uid 2000). Returns output and exit code.',
    schema: obj({ command: { type: 'string' }, timeoutMs: num('default 30000, max 300000') }, ['command']) },
  device_info: { description: 'Model, Android version, screen size, screenshot scale and the focused window.', schema: obj({}) },
};

function defaultExec(bin, args, { timeout = 15000, input } = {}) {
  return new Promise((resolve, reject) => {
    const child = execFile(bin, args, { timeout, encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) { err.stdout = stdout; err.stderr = String(stderr || ''); return reject(err); }
      resolve({ stdout, stderr: String(stderr || '') });
    });
    if (input !== undefined) { child.stdin.on('error', () => {}); child.stdin.end(input); }
  });
}

/** `adb devices` output -> [{serial, state}] */
export function parseDevices(out) {
  return String(out).split('\n').map(l => l.trim())
    .filter(l => l && !l.startsWith('*') && !l.startsWith('List of devices'))
    .map(l => l.split(/\s+/)).filter(p => p.length >= 2).map(([serial, state]) => ({ serial, state }));
}

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
const decode = s => s.replace(/&(#x?[0-9a-fA-F]+|\w+);/g, (m, e) =>
  e[0] === '#' ? String.fromCodePoint(e[1] === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)) : (ENTITIES[e] ?? m));

/** uiautomator XML -> compact element list. Bounds are divided by `scale` (device px -> screenshot px). */
export function parseUiTree(xml, { scale = 1, all = false } = {}) {
  const nodes = [];
  for (const m of String(xml).matchAll(/<node\b([^>]*)>/g)) {
    const a = {};
    for (const [, k, v] of m[1].matchAll(/([\w-]+)="([^"]*)"/g)) a[k] = decode(v);
    const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds || '');
    if (!b) continue;
    const flags = ['clickable', 'long-clickable', 'scrollable', 'checkable', 'checked', 'selected', 'focused', 'password']
      .filter(f => a[f] === 'true');
    if (a.enabled === 'false') flags.push('disabled');
    const interactive = a.clickable === 'true' || a['long-clickable'] === 'true' || a.scrollable === 'true' || a.checkable === 'true';
    if (!all && !a.text && !a['content-desc'] && !interactive) continue;
    const [x1, y1, x2, y2] = b.slice(1).map(n => Math.round(Number(n) / scale));
    const n = { bounds: [x1, y1, x2, y2], center: [Math.round((x1 + x2) / 2), Math.round((y1 + y2) / 2)] };
    if (a.text) n.text = a.text;
    if (a['content-desc']) n.desc = a['content-desc'];
    if (a['resource-id']) n.id = a['resource-id'].replace(/^.*:id\//, '');
    if (a.class) n.class = a.class.replace(/^.*\./, '');
    if (flags.length) n.flags = flags;
    nodes.push(n);
  }
  return nodes;
}

/** width/height from a PNG header, or null if `buf` is not a PNG. */
export function pngSize(buf) {
  if (!buf || buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

const shq = s => `'${String(s).replace(/'/g, `'\\''`)}'`;

export function createControlMcp({ exec = defaultExec, env = process.env } = {}) {
  const adbBin = env.ADB_BIN || 'adb';
  const maxSide = env.CONTROL_MAX_SIDE === undefined || env.CONTROL_MAX_SIDE === '' ? 1280 : Number(env.CONTROL_MAX_SIDE) || 0;
  let resizer;                    // undefined = not probed, null = none, else binary name
  const sizes = new Map();        // serial -> { width, height }

  async function run(bin, args, opts) {
    try { return await exec(bin, args, opts); } catch (e) {
      if (e?.code === 'ENOENT') throw new Error(`${bin} not found: run control/setup.sh (Termux: pkg install android-tools) or set ADB_BIN`);
      if (e?.killed || e?.code === 'ETIMEDOUT') throw new Error(`timeout running ${bin} ${args.slice(0, 4).join(' ')}`);
      throw e;
    }
  }

  async function serial() {
    if (env.ADB_SERIAL) return env.ADB_SERIAL;
    const { stdout } = await run(adbBin, ['devices'], { timeout: 8000 });
    const devs = parseDevices(stdout);
    const d = devs.find(x => x.state === 'device');
    if (d) return d.serial;
    const seen = devs.length ? ` (seen: ${devs.map(x => `${x.serial} ${x.state}`).join(', ')})` : '';
    throw new Error('no adb device connected' + seen + ': open the Claude Droid app -> 폰 제어 (adb) and pair wireless debugging, or run `adb connect localhost:<port>`');
  }

  async function adb(args, opts) { return run(adbBin, ['-s', await serial(), ...args], opts); }

  /** Runs a device shell command; throws with the device's output on a non-zero exit. */
  async function sh(command, opts) {
    try { return String((await adb(['shell', command], opts)).stdout); } catch (e) {
      const out = [e.stdout, e.stderr].map(x => String(x || '').trim()).filter(Boolean).join('\n');
      throw new Error(out || e.message);
    }
  }

  async function findResizer() {
    if (resizer !== undefined) return resizer;
    resizer = null;
    if (maxSide > 0) for (const bin of ['magick', 'convert']) {
      try { if (/ImageMagick/.test(String((await exec(bin, ['-version'], { timeout: 5000 })).stdout))) { resizer = bin; break; } } catch {}
    }
    return resizer;
  }

  async function deviceSize() {
    const s = await serial();
    if (!sizes.has(s)) {
      const out = await sh('wm size');
      const m = /Override size:\s*(\d+)x(\d+)/.exec(out) || /Physical size:\s*(\d+)x(\d+)/.exec(out);
      if (!m) throw new Error('cannot read screen size: ' + out.trim());
      sizes.set(s, { width: Number(m[1]), height: Number(m[2]) });
    }
    return sizes.get(s);
  }

  /** device px per screenshot px. 1 unless screenshots are downsized. Depends only on the device, so it is stable across calls. */
  async function scale() {
    if (!(await findResizer())) return 1;
    const { width, height } = await deviceSize();
    return Math.max(1, Math.max(width, height) / maxSide);
  }

  async function px(args, names) {
    const s = await scale();
    return names.map(n => {
      const v = Number(args[n]);
      if (!Number.isFinite(v) || v < 0) throw new Error(`invalid ${n}: ${args[n]}`);
      return Math.round(v * s);
    });
  }
  const ms = (v, def, max) => { const n = Number(v ?? def); if (!Number.isFinite(n) || n < 0) throw new Error(`invalid duration: ${v}`); return Math.min(Math.round(n), max); };

  const impl = {
    async screenshot() {
      const png = (await adb(['exec-out', 'screencap', '-p'], { timeout: 20000 })).stdout;
      const dev = pngSize(png);
      if (!dev) throw new Error('screencap returned no image (screen off or secure window?)');
      let data = png, mimeType = 'image/png', s = await scale();
      if (s > 1) {
        try {
          data = (await exec(resizer, ['png:-', '-resize', `${maxSide}x${maxSide}>`, '-quality', '80', 'jpg:-'], { timeout: 20000, input: png })).stdout;
          if (!data?.length) throw new Error('empty output');
          mimeType = 'image/jpeg';
        } catch { resizer = null; data = png; s = 1; }   // resizer broken: fall back to full-size PNG from now on
      }
      const info = { width: Math.round(dev.width / s), height: Math.round(dev.height / s), deviceWidth: dev.width, deviceHeight: dev.height, scale: Number(s.toFixed(4)) };
      return { content: [{ type: 'image', data: Buffer.from(data).toString('base64'), mimeType }, { type: 'text', text: JSON.stringify(info) }] };
    },
    async tap(a) { const [x, y] = await px(a, ['x', 'y']); await sh(`input tap ${x} ${y}`); return { ok: true }; },
    async long_press(a) {
      const [x, y] = await px(a, ['x', 'y']); const d = ms(a.durationMs, 800, 10000);
      await sh(`input swipe ${x} ${y} ${x} ${y} ${d}`, { timeout: d + 15000 }); return { ok: true };
    },
    async swipe(a) {
      const [x1, y1, x2, y2] = await px(a, ['x1', 'y1', 'x2', 'y2']); const d = ms(a.durationMs, 300, 10000);
      await sh(`input swipe ${x1} ${y1} ${x2} ${y2} ${d}`, { timeout: d + 15000 }); return { ok: true };
    },
    async type(a) {
      const text = String(a.text);
      if (/[^\x20-\x7e\n]/.test(text)) throw new Error('type supports printable ASCII only (adb `input text` cannot send other characters); use the on-screen keyboard via tap, or paste from the clipboard');
      const lines = text.split('\n');
      for (let i = 0; i < lines.length; i++) {
        if (lines[i]) await sh(`input text ${shq(lines[i].replace(/ /g, '%s'))}`, { timeout: 30000 });
        if (i < lines.length - 1) await sh('input keyevent 66');
      }
      if (a.enter) await sh('input keyevent 66');
      return { ok: true };
    },
    async key(a) {
      const k = String(a.key).trim();
      const code = KEYS[k.toLowerCase()] ?? (/^\d{1,4}$/.test(k) ? Number(k) : /^KEYCODE_[A-Z0-9_]+$/.test(k) ? k : null);
      if (code === null) throw new Error(`unknown key: ${k} (use ${Object.keys(KEYS).join(', ')}, a keycode number or KEYCODE_*)`);
      await sh(`input keyevent ${code}`); return { ok: true, keycode: code };
    },
    async ui_tree(a) {
      let xml = '';
      try { xml = String((await adb(['exec-out', 'uiautomator', 'dump', '/dev/tty'], { timeout: 30000 })).stdout); } catch {}
      if (!xml.includes('<hierarchy')) {
        const f = '/data/local/tmp/droid-control-ui.xml';
        xml = await sh(`uiautomator dump ${f} >/dev/null && cat ${f}; rm -f ${f}`, { timeout: 30000 });
      }
      if (!xml.includes('<hierarchy')) throw new Error('uiautomator dump failed: ' + xml.trim().slice(0, 200));
      const s = await scale();
      const nodes = parseUiTree(xml, { scale: s, all: !!a.all });
      return { count: nodes.length, scale: Number(s.toFixed(4)), nodes };
    },
    async open_app(a) {
      const p = String(a.package);
      if (!/^[A-Za-z0-9_.]+$/.test(p)) throw new Error(`invalid package: ${p}`);
      if (a.activity !== undefined && !/^[A-Za-z0-9_.$]+$/.test(String(a.activity))) throw new Error(`invalid activity: ${a.activity}`);
      const out = await sh(a.activity ? `am start -n ${p}/${a.activity}` : `monkey -p ${p} -c android.intent.category.LAUNCHER 1`, { timeout: 20000 });
      if (/No activities found|Error:|does not exist/.test(out)) throw new Error(out.trim());
      return { ok: true };
    },
    async list_apps(a) {
      const out = await sh(`pm list packages${a.system ? '' : ' -3'}`, { timeout: 20000 });
      const f = a.filter ? String(a.filter).toLowerCase() : '';
      const packages = out.split('\n').map(l => l.trim().replace(/^package:/, '')).filter(p => p && (!f || p.toLowerCase().includes(f))).sort();
      return { count: packages.length, packages };
    },
    async shell(a) {
      const timeout = ms(a.timeoutMs, 30000, 300000);
      try {
        const r = await adb(['shell', String(a.command)], { timeout });
        return { exitCode: 0, output: String(r.stdout) + r.stderr };
      } catch (e) {
        if (typeof e?.code !== 'number') throw e;   // not a plain non-zero exit (timeout, no device, ...)
        return { exitCode: e.code, output: String(e.stdout || '') + String(e.stderr || '') };
      }
    },
    async device_info() {
      const out = await sh('getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; dumpsys window 2>/dev/null | grep -m1 mCurrentFocus');
      const [manufacturer = '', model = '', android = '', sdk = '', focus = ''] = out.split('\n').map(l => l.trim());
      const s = await scale();
      return { serial: await serial(), manufacturer, model, android, sdk: Number(sdk) || null, screen: await deviceSize(), scale: Number(s.toFixed(4)),
        focus: (/mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}/.exec(focus) || [])[1] || focus || null };
    },
  };

  async function call(name, args = {}) {
    const t = TOOLS[name];
    if (!t) throw new Error(`unknown tool: ${name}`);
    for (const r of t.schema.required) if (args[r] === undefined || args[r] === null) throw new Error(`missing argument: ${r}`);
    return impl[name](args);
  }

  const ok = (id, result) => ({ jsonrpc: '2.0', id, result });
  const fail = (id, code, message) => ({ jsonrpc: '2.0', id, error: { code, message } });

  /** returns a response object, or null for notifications */
  async function handle(msg) {
    if (!msg || typeof msg !== 'object' || typeof msg.method !== 'string') {
      return msg && msg.id !== undefined ? fail(msg.id, -32600, 'invalid request') : null;
    }
    const { id, method, params } = msg;
    if (id === undefined || id === null) return null; // notifications are ignored
    switch (method) {
      case 'initialize':
        return ok(id, { protocolVersion: params?.protocolVersion || '2024-11-05', capabilities: { tools: {} },
          serverInfo: { name: 'droid-control', version: '0.7.0' } });
      case 'ping': return ok(id, {});
      case 'tools/list':
        return ok(id, { tools: Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.description, inputSchema: t.schema })) });
      case 'tools/call': {
        const name = params?.name;
        if (!TOOLS[name]) return fail(id, -32602, `unknown tool: ${name}`);
        try {
          const r = await call(name, params.arguments || {});
          return ok(id, r?.content ? r : { content: [{ type: 'text', text: JSON.stringify(r) }] });
        } catch (e) {
          return ok(id, { content: [{ type: 'text', text: String(e.message || e) }], isError: true });
        }
      }
      default: return fail(id, -32601, `method not found: ${method}`);
    }
  }
  return { handle, call };
}

function main() {
  const mcp = createControlMcp();
  const rl = readline.createInterface({ input: process.stdin });
  let pending = 0, ended = false;
  const maybeExit = () => { if (ended && pending === 0) process.exit(0); };
  rl.on('line', async line => {
    if (!line.trim()) return;
    pending++;
    let resp;
    try { resp = await mcp.handle(JSON.parse(line)); }
    catch { resp = { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }; }
    if (resp) process.stdout.write(JSON.stringify(resp) + '\n');
    pending--; maybeExit();
  });
  rl.on('close', () => { ended = true; maybeExit(); });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
