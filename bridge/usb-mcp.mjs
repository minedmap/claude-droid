#!/usr/bin/env node
// Claude Droid USB MCP server (stdio, JSON-RPC 2.0, zero dependencies).
// Talks to the Claude Droid app's USB service over loopback HTTP (X-Token).
import fs from 'node:fs';
import os from 'node:os';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';

const TERMUX_TOKEN = '/data/data/com.termux/files/home/.claude-droid-token';
const DEFAULT_URL = 'http://127.0.0.1:8766';

export function loadToken(env = process.env) {
  if (env.CLAUDE_DROID_TOKEN) return env.CLAUDE_DROID_TOKEN.trim();
  for (const p of [TERMUX_TOKEN, `${os.homedir()}/.claude-droid-token`]) {
    try { const t = fs.readFileSync(p, 'utf8').trim(); if (t) return t; } catch {}
  }
  return '';
}

const dev = { type: 'string', description: 'device id from usb_list (e.g. /dev/bus/usb/001/002)' };
const port = { type: 'integer', description: 'serial port index, default 0' };
const obj = (properties, required = []) => ({ type: 'object', properties, required });

// name -> { description, schema, method, path, timeout }
export const TOOLS = {
  usb_list: { description: 'List USB devices attached via OTG (VID/PID, names, interfaces, endpoints, permission, serial driver).',
    schema: obj({}), method: 'GET', path: '/usb/devices' },
  usb_permission: { description: 'Ask the user (on the phone) to grant USB access to a device. Blocks up to 30 s for the tap.',
    schema: obj({ device: dev }, ['device']), method: 'POST', path: '/usb/permission', timeout: 40000 },
  usb_descriptors: { description: 'Raw USB descriptors (hex) of a device. Needs permission.',
    schema: obj({ device: dev }, ['device']), method: 'GET', path: '/usb/descriptors', query: ['device'] },
  usb_control: { description: 'USB control transfer. IN (requestType bit7 set): give length; OUT: give dataHex.',
    schema: obj({ device: dev, requestType: { type: 'integer' }, request: { type: 'integer' }, value: { type: 'integer' }, index: { type: 'integer' },
      dataHex: { type: 'string' }, length: { type: 'integer' }, timeoutMs: { type: 'integer' } }, ['device', 'requestType', 'request']),
    method: 'POST', path: '/usb/control' },
  usb_transfer: { description: 'Bulk/interrupt transfer on an endpoint (direction follows the endpoint address). Use usb_list for interface ids and endpoint addresses.',
    schema: obj({ device: dev, interface: { type: 'integer' }, endpoint: { type: 'integer', description: 'endpoint address, e.g. 129 for 0x81' },
      dataHex: { type: 'string' }, length: { type: 'integer' }, timeoutMs: { type: 'integer' } }, ['device', 'interface', 'endpoint']),
    method: 'POST', path: '/usb/transfer' },
  serial_open: { description: 'Open a USB serial port (CDC/FTDI/CP210x/CH34x/PL2303) and start buffering input (1 MiB ring).',
    schema: obj({ device: dev, port, baud: { type: 'integer' }, dataBits: { type: 'integer' }, stopBits: { type: 'number' },
      parity: { type: 'string', enum: ['none', 'odd', 'even', 'mark', 'space'] }, dtr: { type: 'boolean' }, rts: { type: 'boolean' } }, ['device']),
    method: 'POST', path: '/serial/open' },
  serial_write: { description: 'Write text or hex bytes to an open serial port.',
    schema: obj({ device: dev, port, text: { type: 'string' }, hex: { type: 'string' } }, ['device']),
    method: 'POST', path: '/serial/write' },
  serial_read: { description: 'Read buffered serial input; waitMs waits for data (max 30000).',
    schema: obj({ device: dev, port, max: { type: 'integer' }, waitMs: { type: 'integer' } }, ['device']),
    method: 'GET', path: '/serial/read', query: ['device', 'port', 'max', 'waitMs'], timeout: 40000 },
  serial_lines: { description: 'Set DTR/RTS on an open serial port (e.g. to reset a board).',
    schema: obj({ device: dev, port, dtr: { type: 'boolean' }, rts: { type: 'boolean' } }, ['device']),
    method: 'POST', path: '/serial/lines' },
  serial_close: { description: 'Close a serial port (also stops its TCP forwarder).',
    schema: obj({ device: dev, port }, ['device']), method: 'POST', path: '/serial/close' },
  serial_tcp: { description: 'Expose a serial port on 127.0.0.1:<tcpPort> for pyserial/esptool/avrdude. mode rfc2217 (esptool --port rfc2217://127.0.0.1:P) or raw (socket://127.0.0.1:P). Returns the port and URL.',
    schema: obj({ device: dev, port, baud: { type: 'integer' }, tcpPort: { type: 'integer', description: '0 = pick free port' },
      mode: { type: 'string', enum: ['raw', 'rfc2217'] } }, ['device']),
    method: 'POST', path: '/serial/tcp' },
  avr_flash: { description: 'Flash an Arduino (uno, nano, nano_old, mega2560) over USB with the built-in STK500 uploader. Pass hexPath (e.g. arduino-cli build output .hex) or hex text.',
    schema: obj({ device: dev, board: { type: 'string', enum: ['uno', 'nano', 'nano_old', 'mega2560'] }, hexPath: { type: 'string' }, hex: { type: 'string' }, port }, ['device']),
    method: 'POST', path: '/flash/avr', timeout: 120000 },
};

export function createUsbMcp({ url = process.env.USB_BRIDGE_URL || DEFAULT_URL, token = loadToken(), fetchImpl = fetch } = {}) {
  const base = url.replace(/\/$/, '');

  async function call(name, args = {}) {
    const t = TOOLS[name];
    if (!t) throw new Error(`unknown tool: ${name}`);
    if (!token) throw new Error('no token: set CLAUDE_DROID_TOKEN or create ~/.claude-droid-token (Claude Droid app token)');
    for (const r of t.schema.required) if (args[r] === undefined) throw new Error(`missing argument: ${r}`);
    let body = { ...args };
    if (name === 'avr_flash') {
      if (!body.hex) {
        if (!body.hexPath) throw new Error('avr_flash needs hexPath or hex');
        try { body.hex = fs.readFileSync(body.hexPath, 'utf8'); } catch (e) { throw new Error(`cannot read hexPath: ${e.message}`); }
      }
      delete body.hexPath;
    }
    let target = base + t.path;
    const init = { method: t.method, headers: { 'X-Token': token }, signal: AbortSignal.timeout(t.timeout || 15000) };
    if (t.method === 'GET') {
      const q = new URLSearchParams();
      for (const k of t.query || []) if (args[k] !== undefined) q.set(k, String(args[k]));
      if ([...q].length) target += '?' + q;
    } else {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let res;
    try { res = await fetchImpl(target, init); } catch (e) {
      if (e?.name === 'TimeoutError' || e?.name === 'AbortError') throw new Error(`timeout calling ${t.path}`);
      throw new Error('Claude Droid 앱의 USB 서비스가 꺼져 있음 (앱을 열어 USB 서비스를 켜고 OTG 연결 확인): ' + (e?.cause?.code || e?.message));
    }
    const text = await res.text();
    let json; try { json = JSON.parse(text); } catch { json = { raw: text }; }
    if (res.status === 401) throw new Error('토큰 불일치(401): 앱 설정의 토큰과 ~/.claude-droid-token 이 같은지 확인');
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
    return json;
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
          serverInfo: { name: 'claude-droid-usb', version: '0.6.0' } });
      case 'ping': return ok(id, {});
      case 'tools/list':
        return ok(id, { tools: Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.description, inputSchema: t.schema })) });
      case 'tools/call': {
        const name = params?.name;
        if (!TOOLS[name]) return fail(id, -32602, `unknown tool: ${name}`);
        try {
          const r = await call(name, params.arguments || {});
          return ok(id, { content: [{ type: 'text', text: JSON.stringify(r) }] });
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
  const mcp = createUsbMcp();
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
