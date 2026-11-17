#!/usr/bin/env node
// Wall-clock benchmark of a control-mcp server over stdio JSON-RPC.   usage: ADB_SERIAL=host:port node bench.mjs [path/to/control-mcp.mjs] [runs]
import { spawn } from 'node:child_process';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const server = process.argv[2] || fileURLToPath(new URL('./control-mcp.mjs', import.meta.url)), runs = Number(process.argv[3] || 5);
const p = spawn('node', [server], { stdio: ['pipe', 'pipe', 'inherit'], env: { CONTROL_AUTO_CONNECT: '0', ...process.env } });
const rl = readline.createInterface({ input: p.stdout }), waiters = new Map();
rl.on('line', l => { const m = JSON.parse(l); waiters.get(m.id)?.(m); });
let id = 0;
const rpc = (method, params = {}) => new Promise(res => { const i = ++id; waiters.set(i, res); p.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n'); });
const call = async (name, args = {}) => {
  const t0 = performance.now(), r = await rpc('tools/call', { name, arguments: args });
  return { ms: performance.now() - t0, bytes: JSON.stringify(r.result.content).length, err: r.result.isError ? r.result.content[0].text : null };
};
const med = a => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)];
await rpc('initialize', { protocolVersion: '2024-11-05' });
const scenarios = {
  screenshot: () => call('screenshot'),
  ui_tree: () => call('ui_tree'),
  'scroll+observe(auto)': () => call('scroll', { direction: 'down', amount: 0.2 }),
  'key+observe(ui)': () => call('key', { key: 'wakeup', observe: 'ui' }),
  'wait_for (already there)': () => call('wait_for', { text: process.env.BENCH_TEXT || 'Search settings', timeoutMs: 15000 }),
  'batch 3 steps+observe(ui)': () => call('batch', { observe: 'ui', steps: [{ tool: 'key', args: { key: 'wakeup' } }, { tool: 'shell', args: { command: 'true' } }, { tool: 'wait_for', args: { text: process.env.BENCH_TEXT || 'Search settings', timeoutMs: 15000 } }] }),
};
await call('ui_tree');   // warm-up
console.log(`server ${server}, ${runs} runs, median ms (first error shown)`);
for (const [name, fn] of Object.entries(scenarios)) {
  const ms = []; let bytes = 0, err = null;
  for (let i = 0; i < runs; i++) { const r = await fn(); ms.push(r.ms); bytes = r.bytes; err ||= r.err; }
  console.log(`${name.padEnd(28)} median ${med(ms).toFixed(0).padStart(5)} ms  (min ${Math.min(...ms).toFixed(0)}, max ${Math.max(...ms).toFixed(0)})  payload ${bytes} B${err ? '  ERR: ' + err.slice(0, 80) : ''}`);
}
p.stdin.end();
