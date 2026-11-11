#!/usr/bin/env node
// Droid Control MCP server (stdio, JSON-RPC 2.0, zero dependencies).
// "Computer use" for an Android device over adb. Works with any MCP client; needs no Claude Droid bridge or token.
//   ADB_BIN              adb binary (default: adb)
//   ADB_SERIAL           device serial (default: first device in `adb devices`)
//   CONTROL_MAX_SIDE     longest screenshot side in px when ImageMagick is available (default 1280, 0 = never resize)
//   CONTROL_OBSERVE      default `observe` for action tools: none | ui | screenshot (default none)
//   CONTROL_SETTLE_MS    wait before observing after an action (default 700)
//   CONTROL_AUTO_WAKE    0 = do not wake the screen before acting (default on)
//   CONTROL_AUTO_CONNECT 0 = do not re-discover/reconnect wireless debugging when the device is gone (default on)
import { execFile } from 'node:child_process';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';

const num = description => ({ type: 'number', description });
const str = description => ({ type: 'string', description });
const bool = description => ({ type: 'boolean', description });
const obj = (properties, required = []) => ({ type: 'object', properties, required });
const XY = 'in screenshot pixels (the coordinate space of `screenshot` and `ui_tree`)';

export const OBSERVE = ['none', 'ui', 'screenshot'];
/** extra arguments every action tool accepts */
const ACT = {
  observe: { type: 'string', enum: OBSERVE, description: 'after acting, wait settleMs and return the new screen: "ui" (compact element list, cheap) or "screenshot". Use it to verify each step without a separate call.' },
  settleMs: num('wait before observing, default 700'),
};
const act = (properties, required) => obj({ ...properties, ...ACT }, required);
const QUERY = {
  text: str('case-insensitive match on the element text or content description (exact matches win over substrings)'),
  id: str('resource id, with or without the "pkg:id/" prefix'),
  exact: bool('only exact matches'),
};

export const KEYS = {
  back: 4, home: 3, recents: 187, enter: 66, backspace: 67, delete: 112, tab: 61, space: 62, escape: 111, menu: 82,
  search: 84, power: 26, wakeup: 224, sleep: 223, volume_up: 24, volume_down: 25, up: 19, down: 20, left: 21, right: 22,
  play_pause: 85, mute: 164, notifications: 83, assist: 219, copy: 278, paste: 279, cut: 277,
};
const POWER_KEYS = new Set([26, 223, 224, 'KEYCODE_POWER', 'KEYCODE_SLEEP', 'KEYCODE_WAKEUP', 'KEYCODE_SOFT_SLEEP']);

export const INSTRUCTIONS = `This server drives a real Android phone over adb. You may operate it directly and autonomously: open apps, navigate, scroll, read and type without asking the user to do it for you, and keep going until the task is done.
Loop: observe -> act -> verify. ui_tree is cheap and gives tap targets; use screenshot for images, layout or when ui_tree is empty (games, web canvases). Prefer tap_element (by text/desc/id) over raw coordinates. Add observe:"ui" (or "screenshot") to an action to get the resulting screen in the same call, use wait_for after slow transitions, and chain predictable steps with batch.
If something is not where you expected, look again (scroll, back, ui_tree all:true) before giving up. shell runs as the adb shell user, so am, pm, cmd, settings, dumpsys, content and input are available for anything the tools do not cover (intents, settings toggles, reading notifications with dumpsys notification).
The server wakes the screen and reconnects wireless debugging by itself. Stop and ask the user only for a secure lock screen, a login/password, or an irreversible or outward action they did not ask for (payments, purchases, deleting data, sending messages or posts to someone new).`;

export const TOOLS = {
  screenshot: { wake: true, description: 'Capture the phone screen. Returns the image plus its size and the scale to device pixels. All coordinates used by the other tools are in this image\'s pixel space.',
    schema: obj({}) },
  ui_tree: { wake: true, description: 'Dump the current screen as a compact list of UI elements (text, desc, id, class, bounds, center, flags). Cheaper than a screenshot and the best way to find tap targets.',
    schema: obj({ all: bool('include elements with no text/desc that are not clickable or scrollable') }) },
  tap_element: { wake: true, action: true, description: 'Find an element by text/desc and/or id in the live UI and tap (or long-press) its center. Fails with the list of visible labels if nothing matches.',
    schema: act({ ...QUERY, index: num('which match to use when several match, default 0'), long: bool('long-press instead of tap') }) },
  tap: { wake: true, action: true, description: 'Tap a point.', schema: act({ x: num(`x ${XY}`), y: num(`y ${XY}`) }, ['x', 'y']) },
  long_press: { wake: true, action: true, description: 'Press and hold a point.',
    schema: act({ x: num(`x ${XY}`), y: num(`y ${XY}`), durationMs: num('hold time, default 800') }, ['x', 'y']) },
  swipe: { wake: true, action: true, description: 'Swipe/drag from one point to another (scroll down = swipe up). For plain scrolling use `scroll`.',
    schema: act({ x1: num(`start x ${XY}`), y1: num(`start y ${XY}`), x2: num('end x'), y2: num('end y'), durationMs: num('default 300') }, ['x1', 'y1', 'x2', 'y2']) },
  scroll: { wake: true, action: true, description: 'Scroll the content: direction "down" shows what is below (finger moves up). Centered on the screen unless x/y is given.',
    schema: act({ direction: { type: 'string', enum: ['up', 'down', 'left', 'right'] }, amount: num('fraction of the screen, 0.1-0.9, default 0.5'),
      x: num(`center x ${XY}`), y: num(`center y ${XY}`) }, ['direction']) },
  type: { wake: true, action: true, description: 'Type text into the focused field (tap the field first). Printable ASCII goes through adb `input text`; any other text (Korean, emoji, ...) is sent through ADBKeyboard (com.android.adbkeyboard) when it is installed, switching the keyboard only for the moment of typing. Newlines are sent as Enter.',
    schema: act({ text: { type: 'string' }, enter: bool('press Enter afterwards'), clear: bool('clear the field first (select all + delete)') }, ['text']) },
  key: { action: true, description: `Press a key: ${Object.keys(KEYS).join(', ')}, a numeric Android keycode, or a KEYCODE_* name.`,
    schema: act({ key: { type: 'string' } }, ['key']) },
  open_app: { wake: true, action: true, description: 'Launch an app by package name (optionally a specific activity). Use list_apps to find the package.',
    schema: act({ package: { type: 'string' }, activity: { type: 'string' } }, ['package']) },
  open_url: { wake: true, action: true, description: 'Open a URL or deep link (https:, tel:, mailto:, geo:, market:, intent schemes, ...) with an ACTION_VIEW intent, optionally in a given app.',
    schema: act({ url: { type: 'string' }, package: str('app to handle it, e.g. com.android.chrome') }, ['url']) },
  wait_for: { wake: true, description: 'Wait until an element matching text/desc and/or id appears (or disappears with gone:true). Use after actions that load or animate.',
    schema: obj({ ...QUERY, gone: bool('wait for it to disappear instead'), timeoutMs: num('default 10000, max 120000') }) },
  batch: { action: true, description: 'Run several tool calls in order in one round trip, e.g. [{"tool":"open_app","args":{"package":"com.android.settings"}},{"tool":"wait_for","args":{"text":"Wi-Fi"}},{"tool":"tap_element","args":{"text":"Wi-Fi"}}]. A {"tool":"sleep","args":{"ms":500}} step pauses. Stops at the first failing step unless continueOnError. Steps do not observe unless they say so; batch\'s own observe runs once at the end.',
    schema: act({ steps: { type: 'array', items: obj({ tool: { type: 'string' }, args: { type: 'object' } }, ['tool']) }, continueOnError: bool('run the remaining steps after a failure') }, ['steps']) },
  list_apps: { description: 'List installed packages (user-installed by default).',
    schema: obj({ system: bool('include system packages'), filter: str('substring filter') }) },
  shell: { description: 'Run a shell command on the device as the adb shell user (uid 2000): am, pm, cmd, settings, dumpsys, content, input, svc, ... Returns output and exit code.',
    schema: obj({ command: { type: 'string' }, timeoutMs: num('default 30000, max 300000') }, ['command']) },
  device_info: { description: 'Model, Android version, screen size, screenshot scale, battery, whether the screen is on, and the focused window.', schema: obj({}) },
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

/** `adb mdns services` output -> ["host:port"] of wireless-debugging connect services */
export function parseMdns(out) {
  return String(out).split('\n').filter(l => l.includes('_adb-tls-connect'))
    .map(l => l.trim().split(/\s+/).pop()).filter(t => /^[\w.\-:[\]]+:\d{1,5}$/.test(t));
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

/** Elements matching {text, id, exact}, best first: exact before substring, clickable before not, then screen order. */
export function findElements(nodes, { text, id, exact } = {}) {
  const q = text === undefined || text === null || text === '' ? null : String(text).toLowerCase();
  const qi = id === undefined || id === null || id === '' ? null : String(id).toLowerCase().replace(/^.*:id\//, '');
  if (q === null && qi === null) throw new Error('give text and/or id');
  const score = (vals, needle) => vals.some(v => v === needle) ? 2 : !exact && vals.some(v => v.includes(needle)) ? 1 : 0;
  const hits = [];
  nodes.forEach((n, i) => {
    let s = 0;
    if (q !== null) { const v = score([n.text, n.desc].filter(Boolean).map(x => x.toLowerCase()), q); if (!v) return; s += v; }
    if (qi !== null) { const v = score(n.id ? [n.id.toLowerCase()] : [], qi); if (!v) return; s += v; }
    if (n.flags?.includes('clickable')) s += 0.5;
    hits.push({ n, s, i });
  });
  return hits.sort((a, b) => b.s - a.s || a.i - b.i).map(h => h.n);
}

/** width/height from a PNG header, or null if `buf` is not a PNG. */
export function pngSize(buf) {
  if (!buf || buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

const shq = s => `'${String(s).replace(/'/g, `'\\''`)}'`;
const ADB_IME = 'com.android.adbkeyboard/.AdbIME';
const WAKE_CMD = `dumpsys power | grep -q 'mWakefulness=Awake' || { input keyevent 224; sleep 0.5; wm dismiss-keyguard >/dev/null 2>&1; echo woke; }`;
const GONE = /device offline|device '[^']*' not found|device not found|no devices\/emulators found|error: closed|connection reset/i;
const describeQuery = a => [a.text != null && `text "${a.text}"`, a.id != null && `id "${a.id}"`].filter(Boolean).join(' + ');

export function createControlMcp({ exec = defaultExec, env = process.env, sleep = t => new Promise(r => setTimeout(r, t)), now = Date.now } = {}) {
  const adbBin = env.ADB_BIN || 'adb';
  const maxSide = env.CONTROL_MAX_SIDE === undefined || env.CONTROL_MAX_SIDE === '' ? 1280 : Number(env.CONTROL_MAX_SIDE) || 0;
  const defaultObserve = OBSERVE.includes(env.CONTROL_OBSERVE) ? env.CONTROL_OBSERVE : 'none';
  const settleEnv = Number(env.CONTROL_SETTLE_MS || NaN);
  const defaultSettle = Number.isFinite(settleEnv) && settleEnv >= 0 ? settleEnv : 700;
  const autoWake = env.CONTROL_AUTO_WAKE !== '0';
  const autoConnectOn = env.CONTROL_AUTO_CONNECT !== '0';
  let resizer;                    // undefined = not probed, null = none, else binary name
  const sizes = new Map();        // serial -> { width, height }
  let awakeAt = -Infinity, connectAt = -Infinity, adbKeyboard;

  async function run(bin, args, opts) {
    try { return await exec(bin, args, opts); } catch (e) {
      if (e?.code === 'ENOENT') throw new Error(`${bin} not found: run control/setup.sh (Termux: pkg install android-tools) or set ADB_BIN`);
      if (e?.killed || e?.code === 'ETIMEDOUT') throw new Error(`timeout running ${bin} ${args.slice(0, 4).join(' ')}`);
      throw e;
    }
  }
  const quiet = async args => { try { return String((await run(adbBin, args, { timeout: 8000 })).stdout); } catch { return ''; } };

  /** Re-discovers wireless debugging (mDNS) and reconnects. At most once per 10 s. Returns whether it ran. */
  async function autoConnect(target) {
    if (!autoConnectOn || now() - connectAt < 10000) return false;
    connectAt = now();
    const targets = new Set(target && /:\d+$/.test(target) ? [target] : []);
    for (const t of parseMdns(await quiet(['mdns', 'services']))) targets.add(t);
    await quiet(['reconnect', 'offline']);
    for (const t of targets) await quiet(['connect', t]);
    return true;
  }

  async function listDevices() { return parseDevices((await run(adbBin, ['devices'], { timeout: 8000 })).stdout); }

  async function serial() {
    if (env.ADB_SERIAL) return env.ADB_SERIAL;
    let devs = await listDevices();
    let d = devs.find(x => x.state === 'device');
    if (!d && await autoConnect()) { devs = await listDevices(); d = devs.find(x => x.state === 'device'); }
    if (d) return d.serial;
    const seen = devs.length ? ` (seen: ${devs.map(x => `${x.serial} ${x.state}`).join(', ')})` : '';
    throw new Error('no adb device connected' + seen + ': open the Claude Droid app -> 폰 제어 (adb) and pair wireless debugging, or run `adb connect localhost:<port>`');
  }

  async function adb(args, opts) {
    const s = await serial();
    try { return await run(adbBin, ['-s', s, ...args], opts); } catch (e) {
      if (!GONE.test(`${e?.stderr || ''} ${e?.message || ''}`) || !(await autoConnect(s))) throw e;
      return run(adbBin, ['-s', await serial(), ...args], opts);   // device came back (maybe on a new port)
    }
  }

  /** Runs a device shell command; throws with the device's output on a non-zero exit. */
  async function sh(command, opts) {
    try { return String((await adb(['shell', command], opts)).stdout); } catch (e) {
      const out = [e.stdout, e.stderr].map(x => String(x || '').trim()).filter(Boolean).join('\n');
      throw new Error(out || e.message);
    }
  }

  /** Turns the screen on (and dismisses an insecure keyguard) if it is off. Checked at most every 3 s. */
  async function awake() {
    if (!autoWake || now() - awakeAt < 3000) return false;
    let woke = false;
    try { woke = (await sh(WAKE_CMD, { timeout: 10000 })).includes('woke'); } catch {}
    awakeAt = now();
    return woke;
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

  /** Current screen size in device px, accounting for rotation. */
  async function screenNow() {
    const { width, height } = await deviceSize();
    let rot = 0;
    try { rot = Number((/SurfaceOrientation:\s*(\d)/.exec(await sh('dumpsys input | grep -m1 SurfaceOrientation')) || [])[1] || 0); } catch {}
    return rot % 2 ? { width: height, height: width } : { width, height };
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

  async function uiNodes(all) {
    let xml = '';
    try { xml = String((await adb(['exec-out', 'uiautomator', 'dump', '/dev/tty'], { timeout: 30000 })).stdout); } catch {}
    if (!xml.includes('<hierarchy')) {
      const f = '/data/local/tmp/droid-control-ui.xml';
      xml = await sh(`uiautomator dump ${f} >/dev/null && cat ${f}; rm -f ${f}`, { timeout: 30000 });
    }
    if (!xml.includes('<hierarchy')) throw new Error('uiautomator dump failed: ' + xml.trim().slice(0, 200));
    const s = await scale();
    return { scale: s, nodes: parseUiTree(xml, { scale: s, all }) };
  }

  const labels = nodes => [...new Set(nodes.flatMap(n => [n.text, n.desc]).filter(Boolean))].slice(0, 30);

  async function hasAdbKeyboard() {
    if (!adbKeyboard) adbKeyboard =(await sh('pm list packages com.android.adbkeyboard')).includes('package:com.android.adbkeyboard');
    return adbKeyboard;
  }

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
    async ui_tree(a) {
      const { scale: s, nodes } = await uiNodes(!!a.all);
      return { count: nodes.length, scale: Number(s.toFixed(4)), nodes };
    },
    async tap_element(a) {
      const { nodes } = await uiNodes(true);
      const hits = findElements(nodes, a);
      const i = a.index === undefined ? 0 : Number(a.index);
      if (!Number.isInteger(i) || i < 0) throw new Error(`invalid index: ${a.index}`);
      if (!hits[i]) throw new Error(`no element matching ${describeQuery(a)}${hits.length ? ` at index ${i} (${hits.length} matches)` : ''}; visible: ${JSON.stringify(labels(nodes))}`);
      const el = hits[i], s = await scale();
      const [x, y] = el.center.map(v => Math.round(v * s));
      await sh(a.long ? `input swipe ${x} ${y} ${x} ${y} 800` : `input tap ${x} ${y}`, { timeout: 20000 });
      return { ok: true, matches: hits.length, element: el };
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
    async scroll(a) {
      const dir = String(a.direction).toLowerCase();
      if (!['up', 'down', 'left', 'right'].includes(dir)) throw new Error(`invalid direction: ${a.direction}`);
      const amount = Math.min(0.9, Math.max(0.1, Number(a.amount ?? 0.5) || 0.5));
      const { width: W, height: H } = await screenNow();
      const s = await scale();
      const pick = (n, def) => {
        if (a[n] === undefined || a[n] === null) return def;
        const v = Number(a[n]);
        if (!Number.isFinite(v) || v < 0) throw new Error(`invalid ${n}: ${a[n]}`);
        return v * s;
      };
      const cx = pick('x', W / 2), cy = pick('y', H / 2);
      const vertical = dir === 'up' || dir === 'down', half = (vertical ? H : W) * amount / 2;
      const sign = dir === 'down' || dir === 'right' ? 1 : -1;   // content moves opposite to the finger
      const clamp = (v, max) => Math.round(Math.min(max - 2, Math.max(1, v)));
      const [x1, y1, x2, y2] = vertical
        ? [clamp(cx, W), clamp(cy + sign * half, H), clamp(cx, W), clamp(cy - sign * half, H)]
        : [clamp(cx + sign * half, W), clamp(cy, H), clamp(cx - sign * half, W), clamp(cy, H)];
      await sh(`input swipe ${x1} ${y1} ${x2} ${y2} 350`, { timeout: 15000 });
      return { ok: true };
    },
    async type(a) {
      const text = String(a.text);
      // select all (Ctrl+A, Android 13+) + delete; older versions: move to the end and delete backwards
      if (a.clear) await sh(`input keycombination 113 29 >/dev/null 2>&1; input keyevent 67; input keyevent 123 ${'67 '.repeat(250).trim()}`, { timeout: 30000 });
      const lines = text.split('\n');
      if (/[^\x20-\x7e\n]/.test(text)) {
        if (!(await hasAdbKeyboard())) throw new Error('non-ASCII text needs ADBKeyboard (com.android.adbkeyboard): install its APK once (e.g. `adb install ADBKeyboard.apk`), then type works for Korean, emoji and any Unicode. Without it: ASCII only, or tap the on-screen keyboard.');
        const parts = lines.map(l => l ? `am broadcast -a ADB_INPUT_B64 --es msg ${Buffer.from(l, 'utf8').toString('base64')} >/dev/null` : 'true');
        const body = parts.join('; input keyevent 66; ');
        await sh(`prev=$(settings get secure default_input_method); ime enable ${ADB_IME} >/dev/null 2>&1; ime set ${ADB_IME} >/dev/null 2>&1; sleep 0.4; ${body}; sleep 0.3; `
          + `if [ -n "$prev" ] && [ "$prev" != null ] && [ "$prev" != '${ADB_IME}' ]; then ime set "$prev" >/dev/null 2>&1; fi; true`, { timeout: 60000 });
      } else {
        for (let i = 0; i < lines.length; i++) {
          if (lines[i]) await sh(`input text ${shq(lines[i].replace(/ /g, '%s'))}`, { timeout: 30000 });
          if (i < lines.length - 1) await sh('input keyevent 66');
        }
      }
      if (a.enter) await sh('input keyevent 66');
      return { ok: true };
    },
    async key(a) {
      const k = String(a.key).trim();
      const code = KEYS[k.toLowerCase()] ?? (/^\d{1,4}$/.test(k) ? Number(k) : /^KEYCODE_[A-Z0-9_]+$/.test(k) ? k : null);
      if (code === null) throw new Error(`unknown key: ${k} (use ${Object.keys(KEYS).join(', ')}, a keycode number or KEYCODE_*)`);
      if (!POWER_KEYS.has(code)) await awake();
      await sh(`input keyevent ${code}`);
      if (POWER_KEYS.has(code)) awakeAt = -Infinity;   // screen state changed: check again next time
      return { ok: true, keycode: code };
    },
    async open_app(a) {
      const p = String(a.package);
      if (!/^[A-Za-z0-9_.]+$/.test(p)) throw new Error(`invalid package: ${p}`);
      if (a.activity !== undefined && !/^[A-Za-z0-9_.$]+$/.test(String(a.activity))) throw new Error(`invalid activity: ${a.activity}`);
      const out = await sh(a.activity ? `am start -n ${p}/${a.activity}` : `monkey -p ${p} -c android.intent.category.LAUNCHER 1`, { timeout: 20000 });
      if (/No activities found|Error:|does not exist/.test(out)) throw new Error(out.trim());
      return { ok: true };
    },
    async open_url(a) {
      const url = String(a.url);
      if (!url || /[\r\n\0]/.test(url) || url.length > 8000) throw new Error('invalid url');
      if (a.package !== undefined && !/^[A-Za-z0-9_.]+$/.test(String(a.package))) throw new Error(`invalid package: ${a.package}`);
      const out = await sh(`am start -W -a android.intent.action.VIEW -d ${shq(url)}${a.package ? ` -p ${a.package}` : ''} 2>&1`, { timeout: 30000 });
      if (/Error:|Exception|unable to resolve/i.test(out)) throw new Error(out.trim());
      return { ok: true };
    },
    async wait_for(a) {
      findElements([], a);   // validates the query
      const timeout = ms(a.timeoutMs, 10000, 120000), start = now();
      for (;;) {
        const { nodes } = await uiNodes(true);
        const hits = findElements(nodes, a);
        if (a.gone ? !hits.length : hits.length) return { ok: true, waitedMs: now() - start, ...(a.gone ? {} : { element: hits[0] }) };
        if (now() - start >= timeout) throw new Error(`timeout after ${timeout}ms waiting for ${describeQuery(a)}${a.gone ? ' to disappear' : ''}; visible: ${JSON.stringify(labels(nodes))}`);
        await sleep(400);
      }
    },
    async batch(a) {
      if (!Array.isArray(a.steps) || !a.steps.length) throw new Error('steps must be a non-empty array');
      if (a.steps.length > 50) throw new Error('at most 50 steps');
      const results = [], images = [];
      let failed = false;
      for (const [i, st] of a.steps.entries()) {
        const tool = st?.tool, args = st?.args && typeof st.args === 'object' ? st.args : {};
        try {
          if (tool === 'sleep') { await sleep(ms(args.ms, 500, 30000)); results.push({ step: i, tool, ok: true }); continue; }
          if (tool === 'batch' || !TOOLS[tool]) throw new Error(`unknown tool: ${tool}`);
          const r = await call(tool, { observe: 'none', ...args });
          if (!r?.content) { results.push({ step: i, tool, result: r }); continue; }
          for (const c of r.content) if (c.type === 'image') images.push(c);
          const texts = r.content.filter(c => c.type === 'text').map(c => { try { return JSON.parse(c.text); } catch { return c.text; } });
          results.push({ step: i, tool, result: texts.length === 1 ? texts[0] : texts });
        } catch (e) {
          results.push({ step: i, tool, error: String(e?.message || e) });
          failed = true;
          if (!a.continueOnError) break;
        }
      }
      const summary = { completed: results.filter(r => !r.error).length, total: a.steps.length, results };
      return { content: [{ type: 'text', text: JSON.stringify(summary) }, ...images], ...(failed ? { isError: true } : {}) };
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
      const out = await sh('getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; '
        + `echo "$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)"; echo "$(dumpsys power 2>/dev/null | grep -m1 -o 'mWakefulness=[A-Za-z]*')"; echo "$(dumpsys battery 2>/dev/null | grep -m1 ' level:')"`);
      const [manufacturer = '', model = '', android = '', sdk = '', focus = '', wake = '', battery = ''] = out.split('\n').map(l => l.trim());
      const s = await scale();
      const lvl = /level:\s*(\d+)/.exec(battery);
      return { serial: await serial(), manufacturer, model, android, sdk: Number(sdk) || null, screen: await deviceSize(), scale: Number(s.toFixed(4)),
        screenOn: wake ? /=Awake/.test(wake) : null, battery: lvl ? Number(lvl[1]) : null,
        focus: (/mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}/.exec(focus) || [])[1] || focus || null };
    },
  };

  async function observe(mode, settleMs) {
    await sleep(settleMs);
    if (mode === 'screenshot') return (await impl.screenshot()).content;
    const t = await impl.ui_tree({});
    return [{ type: 'text', text: JSON.stringify({ screen: t }) }];
  }

  async function call(name, args = {}) {
    const t = TOOLS[name];
    if (!t) throw new Error(`unknown tool: ${name}`);
    for (const r of t.schema.required) if (args[r] === undefined || args[r] === null) throw new Error(`missing argument: ${r}`);
    const mode = t.action ? (args.observe ?? defaultObserve) : 'none';
    if (!OBSERVE.includes(mode)) throw new Error(`invalid observe: ${args.observe} (use ${OBSERVE.join(', ')})`);
    const settle = mode === 'none' ? 0 : ms(args.settleMs, defaultSettle, 10000);
    if (t.wake) await awake();
    const r = await impl[name](args);
    if (mode === 'none') return r;
    if (r?.isError) return r;   // a failed batch: do not bury the error under an observation
    const content = r?.content ? [...r.content] : [{ type: 'text', text: JSON.stringify(r) }];
    content.push(...await observe(mode, settle));
    return { content };
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
          serverInfo: { name: 'droid-control', version: '0.8.0' }, instructions: INSTRUCTIONS });
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
