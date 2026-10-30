import http from 'node:http';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];
const running = new Map(); // reqId -> child

function tmpDir() {
  if (process.env.TMPDIR) return process.env.TMPDIR;
  const d = process.env.PREFIX ? path.join(process.env.PREFIX, 'tmp') : os.tmpdir();
  fs.mkdirSync(d, { recursive: true });
  return d;
}

export function createBridge({ token, claudeBin = 'claude' }) {
  return http.createServer((req, res) => {
    handle(req, res).catch(e => {
      if (!res.headersSent) res.writeHead(400, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: String(e.message || e) }));
      else res.end();
    });
  });

  async function handle(req, res) {
    if (req.headers['x-token'] !== token) { res.writeHead(401).end(); return; }
    const url = new URL(req.url, 'http://x');
    if (req.method === 'POST' && url.pathname === '/upload') return upload(req, res, url);
    if (req.method === 'GET' && url.pathname === '/file') return serveFile(res, url, false);
    if (req.method === 'GET' && url.pathname === '/stat') return serveFile(res, url, true);
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true}'); return;
    }
    if (req.method === 'POST' && req.url === '/cancel') {
      const { reqId } = await readJson(req);
      { const c = running.get(reqId); if (c) killGroup(c); }
      res.writeHead(200).end(); return;
    }
    if (req.method === 'POST' && req.url === '/chat') {
      const { prompt, sessionId, cwd, reqId = String(Date.now()), skipPerms = true, model, effort } = await readJson(req);
      if (typeof prompt !== 'string' || !prompt) throw new Error('prompt required');
      const args = ['-p', prompt, '--output-format', 'stream-json', '--verbose', '--include-partial-messages'];
      if (sessionId) args.push('--resume', sessionId);
      if (typeof model === 'string' && /^[A-Za-z0-9._\-\[\]]{1,64}$/.test(model)) args.push('--model', model);
      if (EFFORTS.includes(effort)) args.push('--effort', effort);
      if (skipPerms) args.push('--dangerously-skip-permissions');
      const dir = cwd && fs.existsSync(cwd) ? cwd : os.homedir();
      const child = spawn(claudeBin, args, { cwd: dir, env: { ...process.env, TMPDIR: tmpDir() }, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
      running.set(reqId, child);
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      const send = obj => res.write(`data: ${JSON.stringify(obj)}\n\n`);
      createInterface({ input: child.stdout }).on('line', l => {
        try { send(JSON.parse(l)); } catch { send({ type: 'raw', text: l }); }
      });
      let err = '';
      child.stderr.on('data', d => { err += d; });
      child.on('error', e => { err += String(e); });
      child.on('close', code => {
        running.delete(reqId);
        send({ type: 'done', code: code ?? -1, stderr: err.slice(-2000) });
        res.end();
      });
      res.on('close', () => { if (!res.writableEnded) killGroup(child); });
      return;
    }
    res.writeHead(404).end();
  }
}

const MAX_BYTES = 50 * 1024 * 1024;
const TYPES = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml',
  md: 'text/markdown; charset=utf-8', json: 'application/json', txt: 'text/plain; charset=utf-8', csv: 'text/csv; charset=utf-8',
  log: 'text/plain; charset=utf-8', html: 'text/html; charset=utf-8', pdf: 'application/pdf',
};
const json = (res, code, obj) => res.writeHead(code, { 'Content-Type': 'application/json' }).end(JSON.stringify(obj));

export function sanitizeName(raw) {
  const base = path.basename(String(raw || '').replace(/\\/g, '/')).replace(/[^A-Za-z0-9._-]/g, '_').replace(/^\.+/, '');
  return base && /[A-Za-z0-9]/.test(base) ? base.slice(0, 100) : 'upload.bin';
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
  const base = cwd && path.isAbsolute(cwd) && fs.existsSync(cwd) ? cwd : os.homedir();
  const dir = path.join(base, '.claude-droid', 'uploads');
  fs.mkdirSync(dir, { recursive: true });
  const target = uniquePath(dir, sanitizeName(url.searchParams.get('name')));
  const out = fs.createWriteStream(target);
  let n = 0, over = false;
  await new Promise((ok, bad) => {
    req.on('data', c => {
      n += c.length;
      if (n > MAX_BYTES) { over = true; return; }
      if (!over) out.write(c);
    });
    req.on('end', () => out.end(ok));
    req.on('error', bad);
    out.on('error', bad);
  });
  if (over) { fs.rmSync(target, { force: true }); return json(res, 413, { error: 'too large' }); }
  json(res, 200, { path: target });
}

function resolveAllowed(p) {
  if (!p || !path.isAbsolute(p)) return { code: 400 };
  let abs = path.resolve(p);
  const rootfs = process.env.UBUNTU_ROOTFS || path.join(process.env.PREFIX || '/data/data/com.termux/files/usr', 'var/lib/proot-distro/installed-rootfs/ubuntu');
  if (abs.startsWith('/root/') && fs.existsSync(path.join(rootfs, 'root'))) abs = path.join(rootfs, abs);
  let real;
  try { real = fs.realpathSync(abs); } catch { real = null; }
  const roots = [os.homedir(), rootfs, '/sdcard', '/storage/emulated/0', os.tmpdir()];
  const under = (x, r) => { let rr = r; try { rr = fs.realpathSync(r); } catch {} return x === rr || x.startsWith(rr + path.sep); };
  // check the lexical path too so missing files outside roots are 403, not 404
  const probe = real ?? abs;
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
    let b = ''; req.on('data', c => { b += c; });
    req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch (e) { bad(e); } });
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  const token = fs.readFileSync(path.join(os.homedir(), '.claude-droid-token'), 'utf8').trim();
  const port = Number(process.env.BRIDGE_PORT) || 8765;
  createBridge({ token, claudeBin: process.env.CLAUDE_BIN || 'claude' }).listen(port, '127.0.0.1', () => console.log(`bridge on 127.0.0.1:${port}`));
}
