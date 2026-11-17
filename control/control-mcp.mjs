#!/usr/bin/env node
// Droid Control MCP server (stdio, JSON-RPC 2.0, zero dependencies).
// "Computer use" for an Android device over adb. Works with any MCP client; needs no Claude Droid bridge or token.
//   ADB_BIN              adb binary (default: adb)
//   ADB_SERIAL           device serial (default: first device in `adb devices`)
//   CONTROL_MAX_SIDE     longest screenshot side in px when ImageMagick is available (default 960, 0 = never resize)
//   CONTROL_JPEG_QUALITY JPEG quality of resized screenshots (default 60)
//   CONTROL_OBSERVE      default `observe` for action tools: none | ui | screenshot | auto (default auto: ui list, plus a screenshot when the tree is near-empty)
//   CONTROL_SETTLE_MS    MAX wait before observing after an action (default 700); the wait ends early as soon as the screen stops changing
//   CONTROL_SETTLE_ADAPTIVE 0 = always wait the full settleMs (default: adaptive)
//   CONTROL_TIMING       1 = log a per-call millisecond breakdown (adb spawns, uiautomator dump, screencap, resize, parse, settle, cdp) to stderr
//   CONTROL_AUTO_WAKE    0 = do not wake the screen before acting (default on)
//   CONTROL_AUTO_CONNECT 0 = do not re-discover/reconnect wireless debugging when the device is gone (default on)
import { execFile } from 'node:child_process';
import { AsyncLocalStorage } from 'node:async_hooks';
import crypto from 'node:crypto';
import http from 'node:http';
import net from 'node:net';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';

const num = description => ({ type: 'number', description });
const str = description => ({ type: 'string', description });
const bool = description => ({ type: 'boolean', description });
const obj = (properties, required = []) => ({ type: 'object', properties, required });
const XY = 'in screenshot pixels (the coordinate space of `screenshot` and `ui_tree`)';

export const OBSERVE = ['none', 'ui', 'screenshot', 'auto'];
/** fewer actionable elements than this = the UI tree is near-empty (canvas/game/Flutter/WebView/FLAG_SECURE): attach a screenshot */
export const SPARSE_BELOW = 3;
/** at most this many elements are listed (visible viewport only) */
export const MAX_LIST = 60;
/** extra arguments every action tool accepts */
const ACT = {
  observe: { type: 'string', enum: OBSERVE, description: 'after acting, wait settleMs and return the new screen: "auto" (default: element list with @refs; a screenshot is added automatically when the UI tree is near-empty, e.g. games/canvas/Flutter/WebView), "ui" (list only), "screenshot" (image only) or "none".' },
  settleMs: num('max wait before observing, default 700; ends early once the screen stops changing'),
};
/** which devtools socket / page the web_* tools talk to */
const WEB_TARGET = {
  package: str('app whose WebView to use (default: the app in the foreground, else Chrome)'),
  socket: str('exact devtools socket name from the error list, e.g. "webview_devtools_remote_1234" or "chrome_devtools_remote"'),
  target: str('page index (0-based) or a substring of its url/title when the app has several pages/tabs (default: the first page)'),
};
const WEB_OBS = {
  observe: { type: 'string', enum: ['web', 'none'], description: '"web" (default): return a fresh web_snapshot after acting; "none": only the result' },
  settleMs: num('wait before the follow-up snapshot, default 300'),
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
Loop: observe -> act -> verify. Every action already returns the new screen (observe "auto"): a compact list of actionable elements with refs like @e3, plus a screenshot automatically when the UI tree is near-empty (games, canvases, Flutter, WebView, secure windows). Tap with tap_element ref:"@e3" (refs come from the LAST ui_tree/observe; after the screen changes take a new snapshot, a stale ref errors). Otherwise tap_element by text/desc/id, or raw x,y from a screenshot. Use screenshot for images and layout. Pass observe:"none" to skip, "ui" for the list only, or "screenshot", use wait_for after slow transitions, and chain predictable steps with batch (steps may include wait_for; its own observe runs once at the end).
Hybrid apps / WebViews / Chrome pages: use web_snapshot, web_click ref:"@w2", web_type and web_eval (Chrome DevTools Protocol) instead of ui_tree: they see the DOM, are far faster than a uiautomator dump, and need no screenshot. Note: each ui_tree/observe costs a uiautomator dump (about 1-2 s on a phone), so prefer batch and observe:"none" for predictable steps.
If something is not where you expected, look again (scroll, back, ui_tree all:true) before giving up. shell runs as the adb shell user, so am, pm, cmd, settings, dumpsys, content and input are available for anything the tools do not cover (intents, settings toggles, reading notifications with dumpsys notification).
The server wakes the screen and reconnects wireless debugging by itself. Stop and ask the user only for a secure lock screen, a login/password, or an irreversible or outward action they did not ask for (payments, purchases, deleting data, sending messages or posts to someone new).`;

export const TOOLS = {
  screenshot: { wake: true, description: 'Capture the phone screen. Returns the image plus its size and the scale to device pixels. All coordinates used by the other tools are in this image\'s pixel space.',
    schema: obj({}) },
  ui_tree: { wake: true, description: 'Snapshot the screen as a compact list of the ACTIONABLE visible elements, each with a short ref: `@e1 button "로그인" [x,y]` (x,y = center in screenshot pixels; flags like checked/disabled follow; duplicate labels get an `in "parent"` or `#n` hint). Visible plain texts follow on one `text:` line. Refs are valid for tap_element until the screen changes; every ui_tree/observe refreshes them. With all:true you get the full raw node dump instead (refs still assigned to actionable nodes). Cheaper than a screenshot; if the tree is near-empty the output says so (use screenshot).',
    schema: obj({ all: bool('full dump: every node with bounds, text, desc, id, class, flags (large); default is the compact actionable list') }) },
  tap_element: { wake: true, action: true, description: 'Tap (or long-press) an element, preferably by `ref` ("@e3" from the last ui_tree/observe list; no re-search; errors if unknown/stale -> take a new ui_tree). Otherwise find it live by text/desc and/or id; fails with the list of visible labels if nothing matches.',
    schema: act({ ref: str('element ref like "@e3" from the LAST ui_tree/observe list (takes precedence over text/id)'), ...QUERY, index: num('which match to use when several match, default 0'), long: bool('long-press instead of tap') }) },
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
  batch: { action: true, description: 'Run several tool calls in order in one round trip, e.g. [{"tool":"open_app","args":{"package":"com.android.settings"}},{"tool":"wait_for","args":{"text":"Wi-Fi"}},{"tool":"tap_element","args":{"text":"Wi-Fi"}}] (steps can be any tool incl. wait_for and web_*; a {"tool":"sleep","args":{"ms":500}} step pauses). Each step runs with observe:"none" (no per-step screen dump), and the batch\'s own `observe` (default auto) runs ONCE at the end after settling, so a whole flow costs one final observation instead of one per step. Use wait_for steps for slow transitions instead of fixed sleeps. Stops at the first failing step unless continueOnError (a failed batch returns without the final observation).',
    schema: act({ steps: { type: 'array', items: obj({ tool: { type: 'string' }, args: { type: 'object' } }, ['tool']) }, continueOnError: bool('run the remaining steps after a failure') }, ['steps']) },
  web_snapshot: { description: 'WebView / Chrome page via Chrome DevTools Protocol (adb forward to the app\'s devtools socket; no npm deps): list the interactive DOM elements of the page that is on screen as `@w1 link "Sign in" [x,y]` (x,y = CSS px in the page viewport, NOT screen pixels; do not pass them to `tap`), plus visible texts. Much more reliable than ui_tree for hybrid apps/WebViews/Chrome pages, which have no useful accessibility tree. The app must have WebView debugging enabled (Chrome always; WebView apps need setWebContentsDebuggingEnabled(true), debuggable builds have it on). Fails with the list of devtools sockets it found if there is none. Refs are valid until the next web_snapshot.',
    schema: obj({ ...WEB_TARGET }) },
  web_click: { description: 'Click a DOM element: ref ("@w3" from the last web_snapshot), CSS selector, or visible text. Scrolls it into view and sends a real mouse click (trusted event). Returns the refreshed web snapshot unless observe:"none".',
    schema: obj({ ref: str('element ref like "@w3" from the LAST web_snapshot'), selector: str('CSS selector (first match)'), text: str('visible text / aria-label / value of the element (interactive elements preferred; exact matches win)'), ...WEB_TARGET, ...WEB_OBS }) },
  web_type: { description: 'Type into a DOM input/textarea/contenteditable (ref, selector or text; focused field if none given). Uses Input.insertText, so any Unicode (Korean, emoji) works without ADBKeyboard. Returns the refreshed web snapshot unless observe:"none".',
    schema: obj({ text: { type: 'string' }, ref: str('element ref like "@w3"'), selector: str('CSS selector'), label: str('visible label/placeholder of the field'), clear: bool('clear the field first'), enter: bool('press Enter afterwards'), ...WEB_TARGET, ...WEB_OBS }, ['text']) },
  web_eval: { description: 'Evaluate a JavaScript expression in the page (awaits promises, returns JSON-serializable values by value). Use for anything the other web_* tools do not cover: scrolling (window.scrollBy), reading text (document.body.innerText), form values, navigation (location.href=...).',
    schema: obj({ expression: { type: 'string' }, ...WEB_TARGET }, ['expression']) },
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

/** GET http://host:port/path with a token header; resolves the body text, rejects on any non-200/timeout/error. */
export function defaultA11yFetch({ port, token, path = '/a11y/tree', timeout = 2500 }) {
  return new Promise((resolve, reject) => {
    const req = http.get({ host: '127.0.0.1', port, path, headers: { 'X-Token': token }, timeout }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => res.statusCode === 200 ? resolve(Buffer.concat(chunks).toString('utf8')) : reject(new Error('a11y http ' + res.statusCode)));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('a11y timeout')));
    req.on('error', reject);
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

/** uiautomator XML -> flat node list with tree links: { i, parent, a (attributes), b ([x1,y1,x2,y2] in screenshot px or null) }. */
function parseTree(xml, scale = 1) {
  const nodes = [], stack = [];
  for (const m of String(xml).matchAll(/<node\b([^>]*?)(\/?)>|<\/node>/g)) {
    if (m[0] === '</node>') { stack.pop(); continue; }
    const a = {};
    for (const [, k, v] of (m[1] || '').matchAll(/([\w-]+)="([^"]*)"/g)) a[k] = decode(v);
    const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds || '');
    nodes.push({ i: nodes.length, parent: stack.length ? stack[stack.length - 1] : -1, a, b: b ? b.slice(1).map(n => Math.round(Number(n) / scale)) : null });
    if (!m[2]) stack.push(nodes.length - 1);
  }
  return nodes;
}
const FLAG_ATTRS = ['clickable', 'long-clickable', 'scrollable', 'checkable', 'checked', 'selected', 'focused', 'password'];
const isInteractive = a => a.clickable === 'true' || a['long-clickable'] === 'true' || a.scrollable === 'true' || a.checkable === 'true';

/** uiautomator XML -> full element list. Bounds are divided by `scale` (device px -> screenshot px). */
export function parseUiTree(xml, { scale = 1, all = false } = {}) {
  const nodes = [];
  for (const t of parseTree(xml, scale)) {
    const { a, b } = t;
    if (!b) continue;
    const flags = FLAG_ATTRS.filter(f => a[f] === 'true');
    if (a.enabled === 'false') flags.push('disabled');
    if (!all && !a.text && !a['content-desc'] && !isInteractive(a)) continue;
    const [x1, y1, x2, y2] = b;
    const n = { bounds: [x1, y1, x2, y2], center: [Math.round((x1 + x2) / 2), Math.round((y1 + y2) / 2)] };
    if (a.text) n.text = a.text;
    if (a['content-desc']) n.desc = a['content-desc'];
    if (a['resource-id']) n.id = a['resource-id'].replace(/^.*:id\//, '');
    if (a.class) n.class = a.class.replace(/^.*\./, '');
    if (flags.length) n.flags = flags;
    Object.defineProperty(n, 'treeIndex', { value: t.i });   // not serialized
    nodes.push(n);
  }
  return nodes;
}

const clip = (s, n) => { s = String(s).replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
const q = s => `"${String(s).replace(/"/g, '\\"')}"`;

function roleOf(a) {
  const c = (a.class || '').replace(/^.*\./, '');
  if (a.scrollable === 'true' && a.clickable !== 'true') return 'scroll';
  if (/EditText|AutoCompleteTextView/.test(c)) return 'input';
  if (/CheckBox/.test(c)) return 'checkbox';
  if (/Switch|ToggleButton/.test(c)) return 'switch';
  if (/RadioButton/.test(c)) return 'radio';
  if (/Spinner/.test(c)) return 'select';
  if (/Button/.test(c)) return 'button';
  if (/Tab/.test(c)) return 'tab';
  if (/TextView/.test(c)) return 'text';
  return a.checkable === 'true' ? 'checkbox' : 'button';
}

/**
 * Compact snapshot of the ACTIONABLE elements inside the visible viewport, each with a short ref (@e1...).
 * Labels fall back to the texts inside the element, then to its resource id; duplicates get a parent hint or an ordinal.
 * Non-actionable visible texts are listed on one trailing line (no refs) so content can still be read.
 */
export function buildSnapshot(xml, { scale = 1, max = MAX_LIST } = {}) {
  const tree = parseTree(xml, scale);
  const kids = tree.map(() => []);
  for (const t of tree) if (t.parent >= 0) kids[t.parent].push(t.i);
  const root = tree.find(t => t.b);
  const view = root ? root.b : [0, 0, 0, 0];
  const [vx1, vy1, vx2, vy2] = view;
  const visible = b => {
    if (!b) return null;
    const c = [Math.max(b[0], vx1), Math.max(b[1], vy1), Math.min(b[2], vx2), Math.min(b[3], vy2)];
    return c[2] > c[0] && c[3] > c[1] ? c : null;
  };
  const actionable = t => isInteractive(t.a) || /EditText|AutoCompleteTextView/.test(t.a.class || '');
  const own = a => a.text || a['content-desc'] || '';
  const used = new Set();
  /** texts inside an element's subtree, not descending into nested actionable elements */
  const inner = (i, out = []) => {
    for (const k of kids[i]) {
      const t = tree[k];
      if (actionable(t)) continue;
      if (visible(t.b) && own(t.a)) out.push(t);
      inner(k, out);
    }
    return out;
  };
  const cands = [];
  for (const t of tree) {
    if (!actionable(t)) continue;
    const c = visible(t.b);
    if (!c) continue;
    const role = roleOf(t.a);
    let label = clip(own(t.a), 60);
    const consumed = [];
    if (!label && role !== 'scroll') {
      const parts = [];
      for (const k of inner(t.i)) { const v = clip(own(k.a), 30); if (v && !parts.includes(v) && parts.length < 3) { parts.push(v); consumed.push(k.i); } }
      label = parts.join(' · ');
    }
    consumed.forEach(i => used.add(i));
    const id = (t.a['resource-id'] || '').replace(/^.*:id\//, '');
    const flags = FLAG_ATTRS.filter(f => ['checked', 'selected', 'focused', 'password'].includes(f) && t.a[f] === 'true');
    if (role === 'scroll') flags.unshift('scrollable');
    if (t.a.enabled === 'false') flags.push('disabled');
    if (t.a['long-clickable'] === 'true' && t.a.clickable !== 'true') flags.push('long-press');
    cands.push({ t, role, label, id, flags, center: [Math.round((c[0] + c[2]) / 2), Math.round((c[1] + c[3]) / 2)], bounds: c });
  }
  // disambiguate duplicates: nearest ancestor that tells them apart (its own label, its id, or a text beside the element), else an ordinal
  const keyOf = e => `${e.role}|${e.label || '#' + e.id}`;
  const groups = new Map();
  for (const e of cands) (groups.get(keyOf(e)) ?? groups.set(keyOf(e), []).get(keyOf(e))).push(e);
  for (const g of groups.values()) {
    if (g.length < 2) continue;
    const hintAt = (e, d) => {
      let p = e.t.parent;
      for (let k = 0; k < d && p >= 0; k++) p = tree[p].parent;
      if (p < 0) return '';
      const pa = tree[p].a;
      return clip(own(pa), 30) || clip(inner(p).map(x => own(x.a)).find(v => v && v !== e.label) || '', 30) || (pa['resource-id'] || '').replace(/^.*:id\//, '');
    };
    for (let d = 1; d <= 5; d++) {   // climb until a hint tells this element apart from all the others in its group
      const hs = g.map(e => e.hint ? '' : hintAt(e, d));
      g.forEach((e, k) => { if (!e.hint && hs[k] && hs.filter(h => h === hs[k]).length === 1) e.hint = `in ${q(hs[k])}`; });
    }
    g.forEach((e, k) => { e.hint ||= `#${k + 1}`; });
  }
  const shown = cands.slice(0, max);
  const items = shown.map((e, k) => {
    const ref = `@e${k + 1}`;
    const name = e.label ? q(e.label) : e.id ? `#${e.id}` : '';
    const disp = [e.role, name, e.hint && `(${e.hint})`].filter(Boolean).join(' ');
    const line = `${ref} ${disp} [${e.center[0]},${e.center[1]}]${e.flags.length ? ' ' + e.flags.join(',') : ''}`;
    return { ref, role: e.role, label: e.label, disp, line, center: e.center, bounds: e.bounds, flags: e.flags, treeIndex: e.t.i };
  });
  const texts = [];
  for (const t of tree) {
    if (used.has(t.i) || actionable(t) || !own(t.a) || !visible(t.b)) continue;
    const v = clip(own(t.a), 50);
    if (!texts.includes(v)) texts.push(v);
  }
  return { screen: [vx2 - vx1, vy2 - vy1], actionable: cands.length, tappable: cands.filter(c => c.role !== 'scroll').length, items, hidden: cands.length - shown.length, texts: texts.slice(0, 25), moreTexts: Math.max(0, texts.length - 25) };
}

/** Text form of a snapshot: header, one line per element, then the visible plain texts. */
export function formatSnapshot(snap, scale = 1) {
  const out = [`screen ${snap.screen[0]}x${snap.screen[1]} scale ${Number(scale.toFixed(4))} | ${snap.items.length} actionable (tap_element ref:"@eN", or tap x,y)`];
  for (const i of snap.items) out.push(i.line);
  if (snap.hidden) out.push(`(+${snap.hidden} more actionable elements not listed: scroll, or ui_tree all:true)`);
  if (snap.texts.length) out.push('text: ' + snap.texts.map(q).join(' | ') + (snap.moreTexts ? ` (+${snap.moreTexts} more)` : ''));
  return out.join('\n');
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

// ---------------------------------------------------------------- Chrome DevTools Protocol (WebView / Chrome), zero dependencies

/** one WebSocket frame (RFC 6455). Clients must mask (`mask: true`), servers must not. */
export function wsFrame(payload, { mask = true, opcode = 1 } = {}) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8');
  const n = data.length;
  const head = n < 126 ? Buffer.from([0x80 | opcode, (mask ? 0x80 : 0) | n])
    : n < 65536 ? Buffer.from([0x80 | opcode, (mask ? 0x80 : 0) | 126, n >> 8, n & 255])
    : (() => { const b = Buffer.alloc(10); b[0] = 0x80 | opcode; b[1] = (mask ? 0x80 : 0) | 127; b.writeBigUInt64BE(BigInt(n), 2); return b; })();
  if (!mask) return Buffer.concat([head, data]);
  const key = crypto.randomBytes(4), body = Buffer.from(data);
  for (let i = 0; i < n; i++) body[i] ^= key[i & 3];
  return Buffer.concat([head, key, body]);
}

/** incremental frame parser: push(chunk) -> [{ opcode, payload }] for each complete message (fragments are joined; control frames come through as-is) */
export function wsParser() {
  let buf = Buffer.alloc(0), frag = null;
  return chunk => {
    buf = Buffer.concat([buf, chunk]);
    const out = [];
    for (;;) {
      if (buf.length < 2) break;
      let len = buf[1] & 127, off = 2;
      if (len === 126) { if (buf.length < 4) break; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) break; len = Number(buf.readBigUInt64BE(2)); off = 10; }
      const masked = (buf[1] & 128) !== 0;
      if (buf.length < off + (masked ? 4 : 0) + len) break;
      const key = masked ? buf.subarray(off, off + 4) : null;
      if (masked) off += 4;
      const payload = Buffer.from(buf.subarray(off, off + len));
      if (key) for (let i = 0; i < len; i++) payload[i] ^= key[i & 3];
      const fin = (buf[0] & 128) !== 0, op = buf[0] & 15;
      buf = buf.subarray(off + len);
      if (op >= 8) { out.push({ opcode: op, payload }); continue; }
      if (op !== 0) frag = { opcode: op, parts: [] };
      if (!frag) continue;
      frag.parts.push(payload);
      if (fin) { out.push({ opcode: frag.opcode, payload: Buffer.concat(frag.parts) }); frag = null; }
    }
    return out;
  };
}

/**
 * WebSocket client -> { send(text), close(), onMessage(fn), onClose(fn) }.
 * Uses node's built-in WebSocket (node >= 22) unless CONTROL_WS=minimal or it does not exist; then a ~40-line client over net.
 */
export function openWs(url, { builtin = typeof WebSocket === 'function' } = {}) {
  if (builtin) return new Promise((resolve, reject) => {
    const ws = new WebSocket(url); let onMsg = () => {}, onClose = () => {}, opened = false;
    ws.onopen = () => { opened = true; resolve({ send: t => ws.send(t), close: () => { try { ws.close(); } catch {} }, onMessage: f => { onMsg = f; }, onClose: f => { onClose = f; } }); };
    ws.onmessage = e => onMsg(String(e.data));
    ws.onerror = () => { if (!opened) reject(new Error(`websocket connect failed: ${url}`)); };
    ws.onclose = () => { if (!opened) reject(new Error(`websocket closed before open: ${url}`)); else onClose(); };
  });
  return new Promise((resolve, reject) => {
    const u = new URL(url), sock = net.connect({ host: u.hostname, port: Number(u.port) || 80 });
    const key = crypto.randomBytes(16).toString('base64'), parse = wsParser();
    let head = Buffer.alloc(0), upgraded = false, onMsg = () => {}, onClose = () => {};
    const api = { send: t => sock.write(wsFrame(t, { mask: true })), close: () => { try { sock.write(wsFrame(Buffer.alloc(0), { mask: true, opcode: 8 })); } catch {} sock.destroy(); }, onMessage: f => { onMsg = f; }, onClose: f => { onClose = f; } };
    const frames = chunk => { for (const f of parse(chunk)) {
      if (f.opcode === 1 || f.opcode === 2) onMsg(f.payload.toString('utf8'));
      else if (f.opcode === 9) sock.write(wsFrame(f.payload, { mask: true, opcode: 10 }));
      else if (f.opcode === 8) sock.end();
    } };
    sock.on('connect', () => sock.write(`GET ${u.pathname}${u.search} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`));
    sock.on('data', chunk => {
      if (upgraded) return frames(chunk);
      head = Buffer.concat([head, chunk]);
      const end = head.indexOf('\r\n\r\n');
      if (end < 0) return;
      if (!/^HTTP\/1\.1 101/.test(head.toString('latin1', 0, end))) { sock.destroy(); return reject(new Error('websocket upgrade refused: ' + head.toString('latin1', 0, Math.min(end, 80)))); }
      upgraded = true; resolve(api);
      const rest = head.subarray(end + 4); if (rest.length) frames(rest);
    });
    sock.on('error', e => { if (!upgraded) reject(e); });
    sock.on('close', () => { if (upgraded) onClose(); else reject(new Error('websocket closed before upgrade')); });
  });
}

/** one JSON GET on 127.0.0.1:port */
const httpJson = (port, path) => new Promise((resolve, reject) => {
  const req = http.get({ host: '127.0.0.1', port, path, timeout: 5000 }, res => {
    const parts = []; res.on('data', c => parts.push(c));
    res.on('end', () => { try { resolve(JSON.parse(Buffer.concat(parts).toString())); } catch (e) { reject(new Error(`devtools ${path}: bad response`)); } });
  });
  req.on('timeout', () => req.destroy(new Error(`devtools ${path}: timeout`)));
  req.on('error', reject);
});

/** devtools sockets from /proc/net/unix text -> ["webview_devtools_remote_123", "chrome_devtools_remote", ...] */
export function parseDevtoolsSockets(out) {
  return [...new Set([...String(out).matchAll(/@([\w.\-]*devtools_remote[\w.\-]*)/g)].map(m => m[1]))];
}

/** In-page script: lists the visible interactive elements and stores them in window.__droidRefs (refs @w1.. index into it). Returns a JSON string. */
export const WEB_SNAPSHOT_JS = `(() => {
  const SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=switch],[role=option],[role=combobox],[role=textbox],[onclick],[tabindex]:not([tabindex="-1"]),[contenteditable=""],[contenteditable="true"]';
  const vw = innerWidth, vh = innerHeight;
  const clip = (s, n) => { s = String(s || '').replace(/\\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
  const rectOf = e => { const r = e.getBoundingClientRect(); if (r.width < 1 || r.height < 1) return null; const cs = getComputedStyle(e); return cs.visibility === 'hidden' || cs.display === 'none' || cs.opacity === '0' ? null : r; };
  const roleOf = e => {
    const t = e.tagName.toLowerCase(), r = e.getAttribute('role');
    if (r) return r === 'textbox' ? 'input' : r;
    if (t === 'a') return 'link';
    if (t === 'select') return 'select';
    if (t === 'textarea' || e.isContentEditable) return 'input';
    if (t === 'input') { const ty = (e.type || 'text').toLowerCase(); return ['button', 'submit', 'reset', 'image'].includes(ty) ? 'button' : ['checkbox', 'radio'].includes(ty) ? ty : ty === 'range' ? 'slider' : 'input'; }
    return 'button';
  };
  const labelOf = e => {
    const t = e.tagName.toLowerCase(), ty = (e.type || '').toLowerCase();
    const lab = e.labels && e.labels[0] ? e.labels[0].innerText : '';
    const byId = e.getAttribute('aria-labelledby') ? [...e.getAttribute('aria-labelledby').split(' ')].map(i => (document.getElementById(i) || {}).innerText).join(' ') : '';
    return clip(e.getAttribute('aria-label') || byId || (t === 'input' || t === 'textarea' || t === 'select' ? (ty === 'password' ? '' : (ty === 'button' || ty === 'submit' ? e.value : '')) || lab || e.placeholder || e.name || '' : e.innerText || e.textContent) || e.alt || e.title || lab || e.name || e.id, 60);
  };
  const all = [...document.querySelectorAll(SEL)];
  const seen = new Set(), cands = [];
  for (const e of all) {
    const r = rectOf(e); if (!r || seen.has(e)) continue; seen.add(e);
    const inView = r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw;
    cands.push({ e, r, inView });
  }
  const shownAll = cands.filter(c => c.inView), off = cands.length - shownAll.length;
  const shown = shownAll.slice(0, ${MAX_LIST});
  window.__droidRefs = shown.map(c => c.e);
  const items = shown.map(c => {
    const e = c.e, flags = [];
    if (e.checked) flags.push('checked');
    if (e.disabled) flags.push('disabled');
    if (e.readOnly) flags.push('readonly');
    if (e.required) flags.push('required');
    if (document.activeElement === e) flags.push('focused');
    if ((e.type || '') === 'password') flags.push('password');
    const v = e.tagName === 'SELECT' ? (e.options[e.selectedIndex] || {}).text : (['input', 'textarea'].includes(e.tagName.toLowerCase()) && e.type !== 'password' && !['button', 'submit', 'checkbox', 'radio'].includes(e.type) ? e.value : '');
    return { role: roleOf(e), label: labelOf(e), value: clip(v, 40), flags, x: Math.round(c.r.left + c.r.width / 2), y: Math.round(c.r.top + c.r.height / 2) };
  });
  const texts = [], tw = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
  for (let n = tw.nextNode(); n && texts.length < 25; n = tw.nextNode()) {
    const p = n.parentElement; if (!p || p.closest('a,button,select,textarea,script,style,noscript,[role=button],[role=link]')) continue;
    const v = clip(n.nodeValue, 50); if (!v) continue;
    const r = rectOf(p); if (!r || !(r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw)) continue;
    if (!texts.includes(v)) texts.push(v);
  }
  return JSON.stringify({ url: location.href, title: document.title, vw, vh, scrollY: Math.round(scrollY), items, offscreen: off, hidden: shownAll.length - shown.length, texts });
})()`;

/** Text form of a web snapshot, same style as the native list. */
export function formatWebSnapshot(s) {
  const seen = new Map();
  for (const i of s.items) { const k = `${i.role}|${i.label}`; seen.set(k, (seen.get(k) || 0) + 1); }
  const idx = new Map();
  const out = [`web ${s.url} "${clip(s.title, 60)}" viewport ${s.vw}x${s.vh} (CSS px) scrollY ${s.scrollY} | ${s.items.length} interactive (web_click ref:"@wN")`];
  s.items.forEach((i, k) => {
    const key = `${i.role}|${i.label}`; let hint = '';
    if (seen.get(key) > 1) { idx.set(key, (idx.get(key) || 0) + 1); hint = ` (#${idx.get(key)})`; }
    const name = i.label ? ` ${q(i.label)}` : '';
    const val = i.value && i.value !== i.label ? ` = ${q(i.value)}` : '';
    out.push(`@w${k + 1} ${i.role}${name}${hint}${val} [${i.x},${i.y}]${i.flags.length ? ' ' + i.flags.join(',') : ''}`);
  });
  if (s.hidden) out.push(`(+${s.hidden} more in view not listed)`);
  if (s.offscreen) out.push(`(+${s.offscreen} interactive elements off-screen: web_eval "window.scrollBy(0, innerHeight*0.8)" or use selector/text)`);
  if (s.texts.length) out.push('text: ' + s.texts.map(q).join(' | '));
  return out.join('\n');
}

/** In-page helper source: (spec) -> element, spec = { ref: "@w3" | selector | text }. */
const WEB_RESOLVE_JS = `((spec) => {
  if (spec.ref !== undefined && spec.ref !== null && spec.ref !== '') {
    const m = /^@?w(\\d+)$/i.exec(String(spec.ref).trim());
    if (!m) throw new Error('invalid ref: ' + spec.ref + ' (expected like "@w3", from web_snapshot)');
    const el = (window.__droidRefs || [])[Number(m[1]) - 1];
    if (!el) throw new Error('unknown ref @w' + m[1] + ': ' + ((window.__droidRefs || []).length ? 'the last snapshot has @w1..@w' + window.__droidRefs.length : 'no snapshot taken in this page') + '. Take a new web_snapshot');
    if (!el.isConnected) throw new Error('stale ref @w' + m[1] + ': the page changed and it is no longer in the DOM. Take a new web_snapshot');
    return el;
  }
  if (spec.selector) { const el = document.querySelector(spec.selector); if (!el) throw new Error('no element matches selector ' + spec.selector); return el; }
  if (spec.text) {
    const needle = String(spec.text).toLowerCase();
    const cand = [...document.querySelectorAll('a,button,input,select,textarea,summary,label,[role],[onclick],[tabindex],h1,h2,h3,h4,li,span,div,p')];
    let best = null, bs = 0;
    for (const e of cand) {
      const r = e.getBoundingClientRect(); if (r.width < 1 || r.height < 1) continue;
      const vals = [e.getAttribute('aria-label'), e.placeholder, e.value, e.innerText, e.title].filter(Boolean).map(v => String(v).replace(/\\s+/g, ' ').trim().toLowerCase());
      let s = vals.some(v => v === needle) ? 4 : vals.some(v => v.includes(needle)) ? 2 : 0;
      if (!s) continue;
      if (e.matches('a,button,input,select,textarea,summary,[role=button],[role=link],[onclick]')) s += 1;
      s -= Math.min(1, (e.innerText || '').length / 2000);   // prefer the innermost / smallest match
      if (s > bs) { bs = s; best = e; }
    }
    if (!best) throw new Error('no element with text ' + JSON.stringify(spec.text));
    return best;
  }
  throw new Error('give ref, selector or text');
})`;

const WEB_SEP = '--droid-web--';
const shq = s => `'${String(s).replace(/'/g, `'\\''`)}'`;
const ADB_IME = 'com.android.adbkeyboard/.AdbIME';
const WAKE_CMD = `dumpsys power | grep -q 'mWakefulness=Awake' || { input keyevent 224; sleep 0.5; wm dismiss-keyguard >/dev/null 2>&1; echo woke; }`;
const GONE = /device offline|device '[^']*' not found|device not found|no devices\/emulators found|error: closed|connection reset/i;
const describeQuery = a => [a.text != null && `text "${a.text}"`, a.id != null && `id "${a.id}"`].filter(Boolean).join(' + ');

export function createControlMcp({ exec = defaultExec, env = process.env, sleep = t => new Promise(r => setTimeout(r, t)), now = Date.now, log = m => process.stderr.write(m + '\n'), a11yFetch = defaultA11yFetch } = {}) {
  const adbBin = env.ADB_BIN || 'adb';
  const maxSide = env.CONTROL_MAX_SIDE === undefined || env.CONTROL_MAX_SIDE === '' ? 960 : Number(env.CONTROL_MAX_SIDE) || 0;
  const jpegQuality = Math.min(100, Math.max(1, Number(env.CONTROL_JPEG_QUALITY) || 60));
  const adaptiveSettle = env.CONTROL_SETTLE_ADAPTIVE !== '0';
  const timingOn = env.CONTROL_TIMING === '1';
  const tstore = new AsyncLocalStorage();
  /** records how long `fn` took under `label` in the current tool call's timing (no-op unless CONTROL_TIMING=1) */
  const timed = async (label, fn) => {
    const rec = tstore.getStore();
    if (!rec) return fn();
    const t0 = performance.now();
    try { return await fn(); } finally { const p = rec.parts.get(label) ?? rec.parts.set(label, { ms: 0, n: 0 }).get(label); p.ms += performance.now() - t0; p.n++; }
  };
  const labelOf = (bin, args) => {
    const a = args[0] === '-s' ? args.slice(2) : args;
    if (bin !== adbBin) return bin;
    if (a[0] === 'exec-out') return a[1] === 'uiautomator' ? 'adb:uiautomator-dump' : `adb:${a[1]}`;
    if (a[0] === 'shell') return `adb:shell ${String(a[1] || '').trim().split(/\s+/)[0].slice(0, 24)}`;
    return `adb:${a[0]}`;
  };
  const defaultObserve = OBSERVE.includes(env.CONTROL_OBSERVE) ? env.CONTROL_OBSERVE : 'auto';
  const settleEnv = Number(env.CONTROL_SETTLE_MS || NaN);
  const defaultSettle = Number.isFinite(settleEnv) && settleEnv >= 0 ? settleEnv : 700;
  const autoWake = env.CONTROL_AUTO_WAKE !== '0';
  const autoConnectOn = env.CONTROL_AUTO_CONNECT !== '0';
  // Optional fast UI tree from the Claude Droid AccessibilityService (127.0.0.1:8767, same token as the bridge).
  // CONTROL_A11Y=0 off, =1 on, unset = on only when CONTROL_A11Y_TOKEN is set. Any failure -> uiautomator dump.
  const a11yToken = env.CONTROL_A11Y_TOKEN || '';
  const a11yOn = env.CONTROL_A11Y === '1' || (env.CONTROL_A11Y !== '0' && !!a11yToken);
  const a11yPort = Number(env.CONTROL_A11Y_PORT) || 8767;
  let a11yDownUntil = -Infinity, a11yForwarded = false;
  let resizer;                    // undefined = not probed, null = none, else binary name
  const sizes = new Map();        // serial -> { width, height }
  let awakeAt = -Infinity, connectAt = -Infinity, adbKeyboard;
  let snap = { items: new Map(), dirty: false };   // ref map of the LAST ui_tree/observe; dirty = an action ran since

  async function run(bin, args, opts) {
    try { return await timed(labelOf(bin, args), () => exec(bin, args, opts)); } catch (e) {
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

  /** Turns the screen on (and dismisses an insecure keyguard) if it is off. Checked at most every 5 s. */
  async function awake() {
    if (!autoWake || now() - awakeAt < 5000) return false;
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

  async function a11yXml() {
    if (!a11yOn || !a11yToken || now() < a11yDownUntil) return '';
    const get = () => a11yFetch({ port: a11yPort, token: a11yToken });
    const ok = x => typeof x === 'string' && x.includes('<hierarchy') ? x : '';
    try { return ok(await get()) || (() => { throw new Error('bad body'); })(); } catch {}
    if (!a11yForwarded) {   // not directly reachable (PC side): forward the device port once, retry
      try { await adb(['forward', `tcp:${a11yPort}`, 'tcp:8767'], { timeout: 8000 }); a11yForwarded = true; return ok(await get()); } catch {}
    }
    a11yDownUntil = now() + 30000;   // do not pay the timeout on every call
    return '';
  }

  async function uiXml() {
    const fast = await a11yXml();
    if (fast) return { xml: fast, scale: await scale() };
    let xml = '';
    try { xml = String((await adb(['exec-out', 'uiautomator', 'dump', '/dev/tty'], { timeout: 30000 })).stdout); } catch {}
    if (!xml.includes('<hierarchy')) {
      const f = '/data/local/tmp/droid-control-ui.xml';
      xml = await sh(`uiautomator dump ${f} >/dev/null && cat ${f}; rm -f ${f}`, { timeout: 30000 });
    }
    if (!xml.includes('<hierarchy')) throw new Error('uiautomator dump failed: ' + xml.trim().slice(0, 200));
    return { xml, scale: await scale() };
  }
  async function uiNodes(all) {
    const { xml, scale: s } = await uiXml();
    return { scale: s, nodes: await timed('parse', async () => parseUiTree(xml, { scale: s, all })) };
  }
  /** dumps the UI, builds the ref list and (unless `store` is false) makes it the LAST snapshot that @refs resolve against */
  async function snapshot({ store = true, xml: given } = {}) {
    const { xml, scale: s } = given ?? await uiXml();
    const built = await timed('parse', async () => buildSnapshot(xml, { scale: s }));
    if (store) snap = { items: new Map(built.items.map(i => [i.ref, i])), dirty: false };
    return { ...built, scale: s, xml, sparse: built.tappable < SPARSE_BELOW,
      text: formatSnapshot(built, s) + (built.tappable < SPARSE_BELOW ? `\nnote: only ${built.tappable} tappable element(s) in the UI tree (game/canvas/Flutter/WebView/secure window?): use screenshot and tap x,y` : '') };
  }
  /** @eN -> element of the last snapshot; if an action ran since, it must still be on screen (matched by role/label/hint) */
  async function resolveRef(ref) {
    const m = /^@?e(\d+)$/i.exec(String(ref).trim());
    if (!m) throw new Error(`invalid ref: ${ref} (expected like "@e3", from ui_tree)`);
    const key = `@e${m[1]}`, it = snap.items.get(key);
    if (!it) throw new Error(`unknown ref ${key}: ${snap.items.size ? `the last snapshot has @e1..@e${snap.items.size}` : 'no snapshot taken yet'}. Re-snapshot with ui_tree (or add observe to an action) and use the refs it returns`);
    if (!snap.dirty) return it;
    const fresh = await snapshot({ store: false });
    const now = fresh.items.find(i => i.disp === it.disp);
    if (!now) throw new Error(`stale ref ${key} (${it.disp}): the screen changed since the last snapshot and it is no longer there. Re-snapshot with ui_tree and use the new refs`);
    return now;
  }

  const labels = nodes => [...new Set(nodes.flatMap(n => [n.text, n.desc]).filter(Boolean))].slice(0, 30);

  async function hasAdbKeyboard() {
    if (!adbKeyboard) adbKeyboard =(await sh('pm list packages com.android.adbkeyboard')).includes('package:com.android.adbkeyboard');
    return adbKeyboard;
  }

  // ---- web (CDP) ---------------------------------------------------------------------------------------------
  const forwards = new Map();   // `${serial}|${socket}` -> local tcp port
  const useBuiltinWs = env.CONTROL_WS !== 'minimal' && typeof WebSocket === 'function';

  /** devtools sockets on the device with the owning package where it can be told: [{ socket, pid, pkg }] */
  async function devtoolsSockets() {
    const out = await sh(`cat /proc/net/unix 2>/dev/null; echo ${WEB_SEP}; for p in $(grep -o 'devtools_remote_[0-9]*' /proc/net/unix | sed 's/.*_//' | sort -u); do echo "$p $(tr '\\0' ' ' </proc/$p/cmdline 2>/dev/null)"; done`, { timeout: 10000 });
    const [unix, pids = ''] = out.split(WEB_SEP);
    const pkgOf = new Map(pids.split('\n').map(l => /^(\d+)\s+(\S+)/.exec(l.trim())).filter(Boolean).map(m => [m[1], m[2]]));
    return parseDevtoolsSockets(unix).map(socket => {
      const pid = (/_(\d+)$/.exec(socket) || [])[1];
      return { socket, pid, pkg: pid ? pkgOf.get(pid) : /^chrome_/.test(socket) ? 'com.android.chrome' : undefined };
    });
  }
  async function focusedPackage() {
    try { return (/mCurrentFocus=Window\{\S+ \S+ ([\w.]+)\//.exec(await sh('dumpsys window 2>/dev/null | grep -m1 mCurrentFocus')) || [])[1]; } catch { return undefined; }
  }
  /** picks the devtools socket: explicit socket, else the package, else the foreground app, else Chrome, else the only/first one */
  let sockCache = null;   // { key, at, pick }: the pick is reused for 5 s (saves a /proc scan + dumpsys per call); dropped when the page cannot be reached
  async function pickSocket(a) {
    const ck = `${a.socket || ''}|${a.package || ''}`;
    if (sockCache && sockCache.key === ck && now() - sockCache.at < 5000) return sockCache.pick;
    const pick = await pickSocketFresh(a);
    sockCache = { key: ck, at: now(), pick };
    return pick;
  }
  async function pickSocketFresh(a) {
    const socks = await devtoolsSockets();
    if (!socks.length) throw new Error('no WebView/Chrome devtools socket on the device (checked /proc/net/unix). The page must be in Chrome, or in an app that called WebView.setWebContentsDebuggingEnabled(true) (debuggable apps have it on); the webview_shell test app only exposes it while a page is open. Native screens: use ui_tree.');
    let pick;
    if (a.socket) pick = socks.find(x => x.socket === String(a.socket));
    else if (a.package) pick = socks.find(x => x.pkg === String(a.package));
    else { const fg = await focusedPackage(); pick = (fg && socks.find(x => x.pkg === fg)) || socks.find(x => x.pkg === 'com.android.chrome') || socks[0]; }
    if (!pick) throw new Error(`no devtools socket for ${a.socket ? 'socket ' + a.socket : 'package ' + a.package}; available: ${JSON.stringify(socks.map(x => x.pkg ? `${x.socket} (${x.pkg})` : x.socket))}`);
    return pick;
  }
  async function forwardPort(socket, fresh) {
    const key = `${await serial()}|${socket}`;
    if (!fresh && forwards.has(key)) return forwards.get(key);
    if (!fresh) {   // reuse a forward left by an earlier run instead of leaking a new one each time
      const list = String((await adb(['forward', '--list'], { timeout: 8000 }).catch(() => ({ stdout: '' }))).stdout).split('\n').filter(l => l.startsWith(key.split('|')[0] + ' ')).join('\n');
      const m = new RegExp(`\\btcp:(\\d+)\\s+localabstract:${socket.replace(/[^\w]/g, '\\$&')}\\s*$`, 'm').exec(list);
      if (m) { forwards.set(key, Number(m[1])); return Number(m[1]); }
    }
    const out = String((await adb(['forward', 'tcp:0', `localabstract:${socket}`], { timeout: 8000 })).stdout).trim();
    const port = Number(/(\d{2,5})/.exec(out)?.[1]);
    if (!port) throw new Error('adb forward failed: ' + out);
    forwards.set(key, port);
    return port;
  }
  async function pageTarget(a) {
    const pick = await pickSocket(a);
    let port, list;
    try { port = await forwardPort(pick.socket); list = await httpJson(port, '/json/list'); } catch {
      sockCache = null;   // the app may have restarted (new pid/socket): rediscover once, with a fresh forward
      const again = await pickSocket(a);
      port = await forwardPort(again.socket, true); list = await httpJson(port, '/json/list');
      return choosePage(a, again, port, list);
    }
    return choosePage(a, pick, port, list);
  }
  function choosePage(a, pick, port, list) {
    const pages = list.filter(t => t.type === 'page' && t.webSocketDebuggerUrl);
    if (!pages.length) throw new Error(`devtools socket ${pick.socket} has no open pages`);
    let t;
    if (a.target === undefined || a.target === null || a.target === '') t = pages[0];
    else if (/^\d+$/.test(String(a.target))) t = pages[Number(a.target)];
    else t = pages.find(p => `${p.url} ${p.title}`.toLowerCase().includes(String(a.target).toLowerCase()));
    if (!t) throw new Error(`no page matching target ${a.target}; pages: ${JSON.stringify(pages.map((p, i) => `${i}: ${clip(p.title, 30)} ${clip(p.url, 60)}`))}`);
    // the forwarded port is local: rewrite whatever host/port the browser reported
    const wsUrl = t.webSocketDebuggerUrl.replace(/^ws:\/\/[^/]+/, `ws://127.0.0.1:${port}`);
    return { wsUrl, page: t, socket: pick.socket };
  }
  /** one CDP connection for the duration of `fn(send)`; send(method, params) -> result */
  async function withPage(a, fn) {
    const { wsUrl } = await timed('cdp:discover', () => pageTarget(a));
    const ws = await timed('cdp:connect', () => openWs(wsUrl, { builtin: useBuiltinWs }));
    const pend = new Map(); let nid = 0;
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      const w = m.id !== undefined && pend.get(m.id);
      if (!w) return;
      pend.delete(m.id); clearTimeout(w.timer);
      m.error ? w.rej(new Error(`${m.error.message}${m.error.data ? ': ' + m.error.data : ''}`)) : w.res(m.result);
    });
    ws.onClose(() => { for (const w of pend.values()) { clearTimeout(w.timer); w.rej(new Error('devtools connection closed')); } pend.clear(); });
    const send = (method, params = {}) => timed('cdp:' + method, () => new Promise((res, rej) => {
      const i = ++nid, timer = setTimeout(() => { pend.delete(i); rej(new Error(`devtools timeout: ${method}`)); }, 20000);
      pend.set(i, { res, rej, timer });
      ws.send(JSON.stringify({ id: i, method, params }));
    }));
    try { return await fn(send); } finally { ws.close(); }
  }
  /** Runtime.evaluate by value; page exceptions become errors */
  async function evalIn(send, expression, extra = {}) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true, ...extra });
    if (r.exceptionDetails) throw new Error(String(r.exceptionDetails.exception?.description || r.exceptionDetails.exception?.value || r.exceptionDetails.text).split('\n')[0].replace(/^Error: /, ''));
    return r.result?.value;
  }
  const webSpec = (a, typing) => ({ ref: a.ref, selector: a.selector, text: typing ? a.label : a.text });
  const hasSpec = sp => [sp.ref, sp.selector, sp.text].some(v => v !== undefined && v !== null && v !== '');
  const takeSnapshot = async send => formatWebSnapshot(JSON.parse(await evalIn(send, WEB_SNAPSHOT_JS)));
  /** follow-up content for a web action: the result plus (unless observe:"none") a fresh snapshot */
  async function webResult(a, send, result) {
    const content = [{ type: 'text', text: JSON.stringify(result) }];
    if (a.observe !== 'none') {
      await sleep(ms(a.settleMs, 300, 10000));
      content.push({ type: 'text', text: await takeSnapshot(send) });
    }
    return { content };
  }

  /** md5 of the current frame computed ON the device (nothing is transferred, ~150 ms); null when screencap cannot be read (secure window, no md5sum) */
  async function frameHash() {
    try {
      const m = /^([0-9a-f]{32})\b/.exec((await sh('screencap | md5sum', { timeout: 6000 })).trim());
      return m && m[1] !== 'd41d8cd98f00b204e9800998ecf8427e' ? m[1] : null;
    } catch { return null; }
  }
  /** waits up to `maxMs` for the screen to stop changing (two identical frames in a row); falls back to the fixed wait when frames cannot be read */
  async function settle(maxMs) {
    if (maxMs <= 0) return;
    await timed('settle', async () => {
      const t0 = now();
      if (!adaptiveSettle) return sleep(maxMs);
      await sleep(Math.min(120, maxMs));   // let the input event start its effect
      let prev = await frameHash();
      if (prev === null) return sleep(Math.max(0, maxMs - (now() - t0)));
      while (now() - t0 < maxMs) {
        await sleep(Math.min(80, Math.max(0, maxMs - (now() - t0))));
        const h = await frameHash();
        if (h === null || h === prev) return;
        prev = h;
      }
    });
  }

  const impl = {
    async screenshot() {
      const png = (await adb(['exec-out', 'screencap', '-p'], { timeout: 20000 })).stdout;
      const dev = pngSize(png);
      if (!dev) throw new Error('screencap returned no image (screen off or secure window?)');
      let data = png, mimeType = 'image/png', s = await scale();
      if (s > 1) {
        try {
          data = (await timed('resize', () => exec(resizer, ['png:-', '-resize', `${maxSide}x${maxSide}>`, '-strip', '-quality', String(jpegQuality), 'jpg:-'], { timeout: 20000, input: png }))).stdout;
          if (!data?.length) throw new Error('empty output');
          mimeType = 'image/jpeg';
        } catch { resizer = null; data = png; s = 1; }   // resizer broken: fall back to full-size PNG from now on
      }
      const info = { width: Math.round(dev.width / s), height: Math.round(dev.height / s), deviceWidth: dev.width, deviceHeight: dev.height, scale: Number(s.toFixed(4)) };
      return { content: [{ type: 'image', data: Buffer.from(data).toString('base64'), mimeType }, { type: 'text', text: JSON.stringify(info) }] };
    },
    async ui_tree(a) {
      const x = await uiXml(), sn = await snapshot({ xml: x });   // refreshes the ref map
      if (!a.all) return { content: [{ type: 'text', text: sn.text }] };
      const refOf = new Map(sn.items.map(i => [i.treeIndex, i.ref]));
      const nodes = parseUiTree(x.xml, { scale: x.scale, all: true }).map(n => refOf.has(n.treeIndex) ? { ref: refOf.get(n.treeIndex), ...n } : n);
      return { count: nodes.length, scale: Number(x.scale.toFixed(4)), nodes };
    },
    async tap_element(a) {
      if (a.ref !== undefined && a.ref !== null && a.ref !== '') {
        const el = await resolveRef(a.ref), s = await scale();
        const [x, y] = el.center.map(v => Math.round(v * s));
        await sh(a.long ? `input swipe ${x} ${y} ${x} ${y} 800` : `input tap ${x} ${y}`, { timeout: 20000 });
        return { ok: true, ref: el.ref, element: el.disp, center: el.center };
      }
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
      let cmd = a.activity ? `am start -n ${p}/${a.activity}` : null;
      if (!cmd) {
        // monkey silently does nothing on some images (e.g. Redroid): resolve the launcher activity and start it directly
        const r = (await sh(`cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${p} 2>/dev/null`, { timeout: 20000 })).trim().split('\n').pop().trim();
        cmd = /^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/.test(r) ? `am start -n ${r}` : `monkey -p ${p} -c android.intent.category.LAUNCHER 1`;
      }
      const out = await sh(cmd, { timeout: 20000 });
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
      let lastHash = null, last = null, first = true, hashOk = false;
      for (;;) {
        // first look: dump at once. Later looks: a cheap frame hash first, the (slow) uiautomator dump only reruns when the screen actually changed
        const h = first ? null : await frameHash();
        first = false;
        let nodes;
        if (h !== null && h === lastHash && last) nodes = last;
        else { nodes = (await uiNodes(true)).nodes; last = nodes; lastHash = h; }
        const hits = findElements(nodes, a);
        hashOk ||= h !== null;
        if (a.gone ? !hits.length : hits.length) return { ok: true, waitedMs: now() - start, ...(a.gone ? {} : { element: hits[0] }) };
        if (now() - start >= timeout) throw new Error(`timeout after ${timeout}ms waiting for ${describeQuery(a)}${a.gone ? ' to disappear' : ''}; visible: ${JSON.stringify(labels(nodes))}`);
        await sleep(hashOk ? 100 : 400);
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
    async web_snapshot(a) { return withPage(a, async send => ({ content: [{ type: 'text', text: await takeSnapshot(send) }] })); },
    async web_eval(a) {
      return withPage(a, async send => {
        const v = await evalIn(send, String(a.expression));
        const text = v === undefined ? 'undefined' : typeof v === 'string' ? v : JSON.stringify(v);
        return { content: [{ type: 'text', text: text.length > 20000 ? text.slice(0, 20000) + `… (+${text.length - 20000} chars)` : text }] };
      });
    },
    async web_click(a) {
      const sp = webSpec(a);
      if (!hasSpec(sp)) throw new Error('give ref, selector or text');
      return withPage(a, async send => {
        const info = JSON.parse(await evalIn(send, `(() => { const el = ${WEB_RESOLVE_JS}(${JSON.stringify(sp)}); el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); const r = el.getBoundingClientRect(); return JSON.stringify({ x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width), h: Math.round(r.height), tag: el.tagName.toLowerCase(), label: (el.getAttribute('aria-label') || el.innerText || el.value || el.placeholder || el.id || '').replace(/\\s+/g, ' ').trim().slice(0, 60) }); })()`));
        if (info.w < 1 || info.h < 1) throw new Error('element has no size on screen (hidden?)');
        const m = { x: info.x, y: info.y, button: 'left', clickCount: 1 };
        await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: info.x, y: info.y });
        await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...m });
        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...m });
        return webResult(a, send, { ok: true, clicked: `${info.tag} ${q(info.label)}`, at: [info.x, info.y] });
      });
    },
    async web_type(a) {
      const sp = webSpec(a, true), text = String(a.text);
      return withPage(a, async send => {
        const focus = hasSpec(sp)
          ? `const el = ${WEB_RESOLVE_JS}(${JSON.stringify(sp)}); el.scrollIntoView({ block: 'center', behavior: 'instant' }); el.focus();`
          : `const el = document.activeElement; if (!el || el === document.body) throw new Error('no focused field: give ref, selector or label');`;
        const clear = a.clear ? `if (el.select) el.select(); else document.execCommand('selectAll'); document.execCommand('delete');` : '';
        const tag = await evalIn(send, `(() => { ${focus} ${clear} return el.tagName.toLowerCase() + (el.type ? ':' + el.type : ''); })()`);
        if (text) await send('Input.insertText', { text });
        if (a.enter) for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type, key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13, ...(type === 'keyDown' ? { text: '\r' } : {}) });
        return webResult(a, send, { ok: true, typed: text.length, into: tag });
      });
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
    await settle(settleMs);
    if (mode === 'screenshot') return (await impl.screenshot()).content;
    let sn;
    try { sn = await snapshot(); } catch (e) {
      if (mode !== 'auto') throw e;
      const shot = (await impl.screenshot()).content;   // tree unavailable (secure window?): the picture is all there is
      return [{ type: 'text', text: `note: UI tree unavailable (${String(e.message || e).slice(0, 120)}); screenshot attached` }, ...shot];
    }
    const out = [{ type: 'text', text: sn.text }];
    if (mode === 'auto' && sn.sparse) {
      try { out.push(...(await impl.screenshot()).content); } catch (e) { out[0].text += `\n(screenshot failed: ${String(e.message || e).slice(0, 120)})`; }
    }
    return out;
  }

  async function call(name, args = {}) {
    const t = TOOLS[name];
    if (!t) throw new Error(`unknown tool: ${name}`);
    for (const r of t.schema.required) if (args[r] === undefined || args[r] === null) throw new Error(`missing argument: ${r}`);
    const mode = t.action ? (args.observe ?? defaultObserve) : 'none';
    if (!OBSERVE.includes(mode)) throw new Error(`invalid observe: ${args.observe} (use ${OBSERVE.join(', ')})`);
    const settle = mode === 'none' ? 0 : ms(args.settleMs, defaultSettle, 10000);
    const rec = timingOn && !tstore.getStore() ? { parts: new Map(), t0: performance.now() } : null;   // nested batch steps fold into the batch's record
    const go = async () => {
      if (t.wake) await awake();
      const r = await impl[name](args);
      if (t.action) snap.dirty = true;   // the screen may have changed: refs must be re-verified until the next snapshot
      if (mode === 'none') return r;
      if (r?.isError) return r;   // a failed batch: do not bury the error under an observation
      const content = r?.content ? [...r.content] : [{ type: 'text', text: JSON.stringify(r) }];
      content.push(...await observe(mode, settle));
      return { content };
    };
    if (!rec) return go();
    try { return await tstore.run(rec, go); } finally {
      const total = performance.now() - rec.t0;
      const parts = [...rec.parts].sort((a, b) => b[1].ms - a[1].ms).map(([k, v]) => `${k}=${v.ms.toFixed(0)}ms${v.n > 1 ? `×${v.n}` : ''}`);
      log(`[timing] ${name} total=${total.toFixed(0)}ms | ${parts.join(' ') || '-'}`);
    }
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
          serverInfo: { name: 'droid-control', version: '0.9.0' }, instructions: INSTRUCTIONS });
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
