import http from 'node:http';
import { spawn, execFile } from 'node:child_process';
import { createInterface } from 'node:readline';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];
const runsById = new Map(); // reqId -> run
const MAX_RUN_BYTES = 20 * 1024 * 1024;
const KEEP_MS = 15 * 60 * 1000;
const MAX_JSON = 10 * 1024 * 1024;

function tmpDir() {
  if (process.env.TMPDIR) return process.env.TMPDIR;
  const d = process.env.PREFIX ? path.join(process.env.PREFIX, 'tmp') : os.tmpdir();
  fs.mkdirSync(d, { recursive: true });
  return d;
}

export function createBridge({ token, claudeBin = 'claude', adbBin = process.env.ADB_BIN || 'adb' }) {
  return http.createServer((req, res) => {
    handle(req, res).catch(e => {
      if (!res.headersSent) res.writeHead(e.status || 400, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: String(e.message || e) }));
      else res.end();
    });
  });

  async function handle(req, res) {
    if (req.headers['x-token'] !== token) { return json(res, 401, { error: 'unauthorized' }); }
    const url = new URL(req.url, 'http://x');
    if (req.method === 'POST' && url.pathname === '/upload') return upload(req, res, url);
    if (req.method === 'GET' && url.pathname === '/file') return serveFile(res, url, false);
    if (req.method === 'GET' && url.pathname === '/stat') return serveFile(res, url, true);
    if (req.method === 'GET' && url.pathname === '/adb') {
      const out = await new Promise(ok => {
        execFile(adbBin, ['devices'], { timeout: 5000 }, (e, so) => ok(e ? '' : String(so)));
      });
      const devices = out.split('\n').slice(1).map(l => l.trim().split(/\s+/)).filter(p => p.length >= 2 && p[0])
        .map(([serial, state]) => ({ serial, state }));
      const connected = devices.some(d => d.state === 'device');
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ connected, devices }));
      return;
    }
    if (req.method === 'GET' && url.pathname === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true}'); return;
    }
    if (req.method === 'GET' && url.pathname === '/runs') {
      const runs = [...runsById.values()].map(r => ({ reqId: r.reqId, startedAt: r.startedAt, done: r.done, code: r.code, events: r.next }));
      return json(res, 200, { runs });
    }
    if (req.method === 'POST' && url.pathname === '/cancel') {
      const { reqId } = await readJson(req);
      { const r = runsById.get(reqId); if (r && !r.done) killGroup(r.child); }
      res.writeHead(200).end(); return;
    }
    if (req.method === 'GET' && url.pathname.startsWith('/chat/')) {
      let id; try { id = decodeURIComponent(url.pathname.slice(6)); } catch { id = ''; }
      const run = runsById.get(id);
      if (!run) return json(res, 404, { error: 'unknown reqId' });
      const from = Math.max(0, parseInt(url.searchParams.get('from') || '0', 10) || 0);
      sseHead(res);
      for (const e of run.events) if (e.idx >= from) res.write(`id: ${e.idx}\ndata: ${e.str}\n\n`);
      if (run.done) { res.end(); return; }
      run.subs.add(res);
      res.on('close', () => run.subs.delete(res));
      return;
    }
    if (req.method === 'POST' && url.pathname === '/chat') {
      const { prompt, sessionId, cwd, reqId = String(Date.now()), skipPerms = true, model, effort } = await readJson(req);
      if (typeof prompt !== 'string' || !prompt) return json(res, 400, { error: 'prompt required' });
      const hasModel = model != null && model !== '';
      if (hasModel && !(typeof model === 'string' && /^[A-Za-z0-9._\-\[\]]{1,64}$/.test(model))) return json(res, 400, { error: 'invalid model' });
      const hasEffort = effort != null && effort !== '';
      if (hasEffort && !EFFORTS.includes(effort)) return json(res, 400, { error: 'invalid effort' });
      const old = runsById.get(reqId);
      if (old && !old.done) return json(res, 409, { error: 'reqId in use' });
      const args = ['-p', '--output-format', 'stream-json', '--verbose', '--include-partial-messages'];
      if (sessionId) args.push('--resume', sessionId);
      if (hasModel) args.push('--model', model);
      if (hasEffort) args.push('--effort', effort);
      if (skipPerms) args.push('--dangerously-skip-permissions');
      const { dir, guestCwd } = chatCwd(cwd, claudeBin);
      const child = spawn(claudeBin, args, { cwd: dir, env: { ...process.env, TMPDIR: tmpDir(), ...(guestCwd ? { CLAUDE_DROID_CWD: guestCwd } : {}) }, stdio: ['pipe', 'pipe', 'pipe'], detached: true });
      child.stdin.on('error', () => {});
      child.stdin.end(prompt);
      const run = { reqId: String(reqId), startedAt: Date.now(), done: false, code: null, events: [], bytes: 0, next: 0, subs: new Set(), child };
      if (old) clearTimeout(old.timer);
      runsById.set(run.reqId, run);
      sseHead(res);
      run.subs.add(res);
      res.on('close', () => run.subs.delete(res)); // disconnect never kills claude; only /cancel does
      const emit = obj => {
        const str = JSON.stringify(obj), idx = run.next++;
        run.events.push({ idx, str, size: str.length });
        run.bytes += str.length;
        while (run.bytes > MAX_RUN_BYTES && run.events.length > 1) run.bytes -= run.events.shift().size;
        for (const s of run.subs) s.write(`id: ${idx}\ndata: ${str}\n\n`);
      };
      let err = '', pending = 2;
      const finish = code => {
        const stderr = err.split('\n').filter(l => !/^proot (warning|info):/.test(l)).join('\n');
        emit({ type: 'done', code, stderr: stderr.slice(-2000) });
        run.done = true; run.code = code;
        for (const s of run.subs) s.end();
        run.subs.clear();
        run.timer = setTimeout(() => { if (runsById.get(run.reqId) === run) runsById.delete(run.reqId); }, KEEP_MS);
        run.timer.unref();
      };
      let exitCode = -1;
      const part = () => { if (--pending === 0) finish(exitCode); };
      const rl = createInterface({ input: child.stdout });
      rl.on('line', l => { try { emit(JSON.parse(l)); } catch { emit({ type: 'raw', text: l }); } });
      rl.on('close', part);
      child.stderr.on('data', d => { err += d; });
      child.on('error', e => { err += String(e); if (child.pid === undefined) { exitCode = -1; pending = 1; part(); } });
      child.on('close', code => { exitCode = code ?? -1; part(); });
      return;
    }
    json(res, 404, { error: 'not found' });
  }
}

function sseHead(res) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
  res.flushHeaders?.();
}

// proot cwd (e.g. /root/proj): run in its host path and tell the wrapper the guest path via CLAUDE_DROID_CWD.
// Only when the wrapper understands that variable; old wrappers would pass the host path to --work-dir.
function chatCwd(cwd, claudeBin) {
  if (!cwd || typeof cwd !== 'string' || !path.isAbsolute(cwd)) return { dir: os.homedir() };
  if (fs.existsSync(cwd) && passthrough(cwd)) return { dir: cwd };
  const host = toHost(path.resolve(cwd));
  let aware = false;
  try { aware = fs.readFileSync(claudeBin, 'utf8').includes('CLAUDE_DROID_CWD'); } catch {}
  if (host !== cwd && aware && fs.existsSync(host)) return { dir: host, guestCwd: path.resolve(cwd) };
  return { dir: fs.existsSync(cwd) ? cwd : os.homedir() };
}

const MAX_BYTES = 50 * 1024 * 1024;
const TYPES = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml', bmp: 'image/bmp', heic: 'image/heic', heif: 'image/heif',
  md: 'text/markdown; charset=utf-8', json: 'application/json', txt: 'text/plain; charset=utf-8', csv: 'text/csv; charset=utf-8',
  log: 'text/plain; charset=utf-8', html: 'text/html; charset=utf-8', pdf: 'application/pdf',
};
const json = (res, code, obj) => res.writeHead(code, { 'Content-Type': 'application/json' }).end(JSON.stringify(obj));

export function sanitizeName(raw) {
  const base = path.basename(String(raw || '').replace(/\\/g, '/')).replace(/[^\p{L}\p{N} ._()\-]/gu, '_').replace(/^\.+/, '').trim();
  return base && /[\p{L}\p{N}]/u.test(base) ? [...base].slice(0, 100).join('') : 'upload.bin';
}

function uniquePath(dir, name) {
  const ext = path.extname(name), stem = name.slice(0, name.length - ext.length);
  for (let i = 0; ; i++) {
    const p = path.join(dir, i === 0 ? name : `${stem}-${i}${ext}`);
    try { fs.writeFileSync(p, '', { flag: 'wx' }); return p; } catch (e) { if (e.code !== 'EEXIST') throw e; }
  }
}

async function upload(req, res, url) {
  const cwd = url.searchParams.get('cwd');
  const hostCwd = cwd && path.isAbsolute(cwd) ? toHost(path.resolve(cwd)) : null;
  const found = hostCwd && fs.existsSync(hostCwd);
  const base = found ? hostCwd : os.homedir();
  const dir = path.join(base, '.claude-droid', 'uploads');
  fs.mkdirSync(dir, { recursive: true });
  const target = uniquePath(dir, sanitizeName(url.searchParams.get('name')));
  const out = fs.createWriteStream(target);
  let n = 0, over = false, ended = false;
  try {
    await new Promise((ok, bad) => {
      req.on('data', c => {
        n += c.length;
        if (n > MAX_BYTES) { over = true; return; }
        if (!over) out.write(c);
      });
      req.on('end', () => { ended = true; out.end(ok); });
      req.on('aborted', () => bad(new Error('aborted')));
      req.on('close', () => { if (!ended) bad(new Error('aborted')); });
      req.on('error', bad);
      out.on('error', bad);
    });
  } catch (e) {
    out.destroy();
    fs.rmSync(target, { force: true });
    if (!res.headersSent && !res.destroyed) json(res, 400, { error: String(e.message || e) });
    return;
  }
  if (over) { fs.rmSync(target, { force: true }); return json(res, 413, { error: 'too large' }); }
  json(res, 200, cwd && !found ? { path: toGuest(target), warning: 'cwd not found, saved to home' } : { path: toGuest(target) });
}

const PREFIX = () => process.env.PREFIX || '/data/data/com.termux/files/usr';
export function ubuntuRootfs() {
  if (process.env.UBUNTU_ROOTFS) return process.env.UBUNTU_ROOTFS;
  const pd = path.join(PREFIX(), 'var/lib/proot-distro');
  const cands = [path.join(pd, 'containers/ubuntu/rootfs'), path.join(pd, 'installed-rootfs/ubuntu')];
  return cands.find(c => fs.existsSync(c)) || cands[0];
}
// paths proot shares 1:1 with Termux: never remap
function passthrough(abs) {
  return ['/data/data/com.termux', '/sdcard', '/storage', os.homedir()].some(r => abs === r || abs.startsWith(r + '/'));
}
/** guest (proot) path -> host (Termux) path. /root always maps; others only when missing on host but present in rootfs. */
export function toHost(abs) {
  if (passthrough(abs)) return abs;
  const rootfs = ubuntuRootfs();
  const cand = path.join(rootfs, abs);
  if (abs === '/root' || abs.startsWith('/root/')) return fs.existsSync(path.join(rootfs, 'root')) ? cand : abs;
  return !fs.existsSync(abs) && fs.existsSync(cand) ? cand : abs;
}
/** host path inside rootfs -> guest path (what Claude sees) */
export function toGuest(p) {
  const rootfs = ubuntuRootfs();
  return p.startsWith(rootfs + '/') ? p.slice(rootfs.length) : p;
}

const DENY_NAMES = new Set(['.credentials.json', '.claude-droid-token', '.env', '.git-credentials', '.netrc', 'adbkey', 'adbkey.pub']);
function denied(x) {
  const segs = x.split(/[\\/]/);
  const b = segs[segs.length - 1].toLowerCase();
  if (DENY_NAMES.has(b) && (b !== 'adbkey' && b !== 'adbkey.pub' || segs.includes('.android'))) return true;
  if (b.endsWith('.pem') || b.endsWith('.key')) return true;
  return segs.slice(0, -1).includes('.ssh') || segs.includes('.ssh');
}

function resolveAllowed(p) {
  if (!p || !path.isAbsolute(p)) return { code: 400 };
  const abs = toHost(path.resolve(p));
  const rootfs = ubuntuRootfs();
  let real;
  try { real = fs.realpathSync(abs); } catch { real = null; }
  const roots = [os.homedir(), rootfs, '/sdcard', '/storage/emulated/0', os.tmpdir()];
  const under = (x, r) => { let rr = r; try { rr = fs.realpathSync(r); } catch {} return x === rr || x.startsWith(rr + path.sep); };
  // check the lexical path too so missing files outside roots are 403, not 404
  const probe = real ?? abs;
  if (denied(abs) || denied(probe)) return { code: 403 };
  if (!roots.some(r => under(probe, r))) return { code: 403 };
  return { code: 0, real };
}

function serveFile(res, url, statOnly) {
  const r = resolveAllowed(url.searchParams.get('path'));
  if (r.code) return json(res, r.code, { error: r.code === 403 ? 'forbidden' : 'absolute path required' });
  const st = r.real ? fs.statSync(r.real) : null;
  if (statOnly) return json(res, 200, st ? { exists: true, size: st.size, isDir: st.isDirectory() } : { exists: false, size: 0, isDir: false });
  if (!st || st.isDirectory()) return json(res, 404, { error: 'not found' });
  if (st.size > MAX_BYTES) return json(res, 413, { error: 'too large' });
  const ext = path.extname(r.real).slice(1).toLowerCase();
  res.writeHead(200, { 'Content-Type': TYPES[ext] || 'application/octet-stream', 'Content-Length': st.size });
  fs.createReadStream(r.real).on('error', () => res.destroy()).pipe(res);
}

function killGroup(child, sig = 'SIGINT') {
  try { process.kill(-child.pid, sig); } catch { try { child.kill(sig); } catch {} }
  setTimeout(() => { if (child.exitCode === null) { try { process.kill(-child.pid, 'SIGKILL'); } catch {} } }, 3000).unref();
}

function readJson(req) {
  return new Promise((ok, bad) => {
    let b = '', n = 0, dead = false;
    req.on('data', c => {
      if (dead) return;
      n += c.length;
      if (n > MAX_JSON) { dead = true; b = ''; bad(Object.assign(new Error('body too large'), { status: 413 })); return; }
      b += c;
    });
    req.on('end', () => { if (dead) return; try { ok(JSON.parse(b || '{}')); } catch (e) { bad(e); } });
    req.on('error', bad);
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  const token = fs.readFileSync(path.join(os.homedir(), '.claude-droid-token'), 'utf8').trim();
  const port = Number(process.env.BRIDGE_PORT) || 8765;
  createBridge({ token, claudeBin: process.env.CLAUDE_BIN || 'claude' }).listen(port, '127.0.0.1', () => console.log(`bridge on 127.0.0.1:${port}`));
}
