import http from 'node:http';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const running = new Map(); // reqId -> child

function tmpDir() {
  if (process.env.TMPDIR) return process.env.TMPDIR;
  const d = process.env.PREFIX ? path.join(process.env.PREFIX, 'tmp') : os.tmpdir();
  fs.mkdirSync(d, { recursive: true });
  return d;
}

export function createBridge({ token, claudeBin = 'claude' }) {
  return http.createServer(async (req, res) => {
    if (req.headers['x-token'] !== token) { res.writeHead(401).end(); return; }
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true}'); return;
    }
    if (req.method === 'POST' && req.url === '/cancel') {
      const { reqId } = await readJson(req);
      running.get(reqId)?.kill('SIGINT');
      res.writeHead(200).end(); return;
    }
    if (req.method === 'POST' && req.url === '/chat') {
      const { prompt, sessionId, cwd, reqId = String(Date.now()), skipPerms = true } = await readJson(req);
      const args = ['-p', prompt, '--output-format', 'stream-json', '--verbose'];
      if (sessionId) args.push('--resume', sessionId);
      if (skipPerms) args.push('--dangerously-skip-permissions');
      const dir = cwd && fs.existsSync(cwd) ? cwd : os.homedir();
      const child = spawn(claudeBin, args, { cwd: dir, env: { ...process.env, TMPDIR: tmpDir() } });
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
      res.on('close', () => { if (!res.writableEnded) child.kill('SIGINT'); });
      return;
    }
    res.writeHead(404).end();
  });
}

function readJson(req) {
  return new Promise((ok, bad) => {
    let b = ''; req.on('data', c => { b += c; });
    req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch (e) { bad(e); } });
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  const token = fs.readFileSync(path.join(os.homedir(), '.claude-droid-token'), 'utf8').trim();
  createBridge({ token, claudeBin: process.env.CLAUDE_BIN || 'claude' }).listen(8765, '127.0.0.1', () => console.log('bridge on 127.0.0.1:8765'));
}
