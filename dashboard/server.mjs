#!/usr/bin/env node
/**
 * The dashboard's small backend ("backend for frontend"). Serves the built Angular app and forwards a
 * fixed list of API calls. Browsers cannot keep secrets, so the WSO2 client secrets (read-only
 * "dashboard-x" applications) stay here and the browser never sees a token.
 *
 *   node dashboard/server.mjs          -> http://localhost:8095   (after: cd dashboard && npm run build)
 *
 * Routes (nothing else is forwarded, so this is not an open proxy; numbers in query strings are checked):
 *   GET  /api/dc/summary                    -> DC  GET  /summary
 *   GET  /api/dc/data?branch=x&size=n       -> DC  GET  /data?branch=X&page=0&size=n     (n: 1..50)
 *   GET  /api/dc/sync-log?limit=n           -> DC  GET  /sync-log?limit=n
 *   POST /api/dc/{sync|purge}/{a|b}         -> DC  POST /{sync|purge}/{X}
 *   POST /api/dc/rush/{a|b}?count=n         -> DC  POST /rush/{X}?count=n                 (n: 1..20000)
 *   GET  /api/branches/{a|b}/summary?from&to -> branch x's WSO2 gateway GET /branch-x/1.0.0/summary?from=&to=
 *   GET  /api/branches/{a|b}/data?size=n    -> branch x's WSO2 gateway GET /branch-x/1.0.0/data?page=0&size=n
 * Branch calls carry a client-credentials token (scope branch_x:read) from that branch's own WSO2.
 *
 * Reads infra/wso2/generated/<x>/dashboard.env and signing-cert.pem (written by infra/wso2/setup.mjs and
 * init-keys.mjs). HTTPS to WSO2 trusts only that instance's certificate.
 */
import { existsSync, readFileSync, statSync } from 'node:fs';
import { createServer as createHttpServer, request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/**
 * How long to wait for an answer. Reads are quick. A command waits longer: the DC answers sync / purge / rush only
 * once Kafka has stored every event (up to 75 s, PassEventPublisher), and giving up earlier would report a failure
 * for a request that then succeeds (seen in Phase 7: "Sync A failed: ETIMEDOUT", yet all 144 events arrived).
 */
const TIMEOUTS = { readMs: 5000, commandMs: 90000 };
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.ico': 'image/x-icon', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.txt': 'text/plain',
};

/**
 * @param {object} config
 * @param {string} config.dcUrl       e.g. http://localhost:8090
 * @param {Record<string, {tokenUrl: string, gatewayUrl: string, clientId?: string, clientSecret?: string,
 *                         scope?: string, ca?: string}>} config.sites   keyed by lower-case branch id
 * @param {string} [config.staticDir] built Angular app (index.html is served for unknown non-API paths)
 * @param {{readMs: number, commandMs: number}} [config.timeouts] (tests shorten them)
 */
export function createServer({ dcUrl, sites, staticDir, timeouts = TIMEOUTS }) {
  const tokens = {}; // site -> { value, renewAt }
  const call = (target, options = {}) => request(target, { timeoutMs: timeouts.readMs, ...options });

  async function token(site) {
    const s = sites[site];
    const cached = tokens[site];
    if (cached && Date.now() < cached.renewAt) return cached.value;
    const res = await call(s.tokenUrl, {
      method: 'POST',
      ca: s.ca,
      headers: {
        Authorization: `Basic ${Buffer.from(`${s.clientId}:${s.clientSecret}`).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({ grant_type: 'client_credentials', scope: s.scope }).toString(),
    });
    if (res.status !== 200) throw new Upstream(502, `WSO2-${site.toUpperCase()} token endpoint answered ${res.status}`);
    const body = JSON.parse(res.body);
    // Renew a minute early so a token never expires on the way to the gateway.
    tokens[site] = { value: body.access_token, renewAt: Date.now() + Math.max(0, body.expires_in - 60) * 1000 };
    return body.access_token;
  }

  async function branchGet(site, pathAndQuery) {
    const s = sites[site];
    if (!s.clientId || !s.clientSecret) {
      throw new Upstream(503, `No WSO2 client for branch ${site.toUpperCase()}. Run: node infra/wso2/setup.mjs`);
    }
    const get = async () => call(`${s.gatewayUrl}${pathAndQuery}`, { ca: s.ca, headers: { Authorization: `Bearer ${await token(site)}` } });
    let res = await get();
    if (res.status === 401) { // token revoked or WSO2 recreated: get a new one, once
      delete tokens[site];
      res = await get();
    }
    return res;
  }

  async function api(req, url) {
    const path = url.pathname;
    let m;
    const q = url.searchParams;
    if (req.method === 'GET' && path === '/api/dc/summary') return call(`${dcUrl}/summary`);
    if (req.method === 'GET' && path === '/api/dc/data' && sites[(q.get('branch') ?? '').toLowerCase()]) {
      return call(`${dcUrl}/data?branch=${q.get('branch').toUpperCase()}&page=0&size=${int(q, 'size', 5, 1, 50)}`);
    }
    if (req.method === 'GET' && path === '/api/dc/sync-log') {
      return call(`${dcUrl}/sync-log?limit=${int(q, 'limit', 20, 1, 200)}`);
    }
    if (req.method === 'POST' && (m = /^\/api\/dc\/(sync|purge|rush)\/([a-z])$/i.exec(path)) && sites[m[2].toLowerCase()]) {
      const count = m[1].toLowerCase() === 'rush' ? `?count=${int(q, 'count', 1000, 1, 20000)}` : '';
      return call(`${dcUrl}/${m[1].toLowerCase()}/${m[2].toUpperCase()}${count}`, { method: 'POST', timeoutMs: timeouts.commandMs });
    }
    if (req.method === 'GET' && (m = /^\/api\/branches\/([a-z])\/(summary|data)$/i.exec(path)) && sites[m[1].toLowerCase()]) {
      const site = m[1].toLowerCase();
      if (m[2].toLowerCase() === 'data') return branchGet(site, `/data?page=0&size=${int(q, 'size', 5, 1, 50)}`);
      const from = int(q, 'from', null, 0, Number.MAX_SAFE_INTEGER);
      const to = int(q, 'to', null, 0, Number.MAX_SAFE_INTEGER);
      return branchGet(site, from !== null && to !== null ? `/summary?from=${from}&to=${to}` : '/summary');
    }
    return { status: 404, body: JSON.stringify({ status: 404, detail: `No such dashboard API: ${req.method} ${path}` }) };
  }

  return createHttpServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) {
      let result;
      try {
        result = await api(req, url);
      } catch (e) {
        const status = e instanceof Upstream ? e.status : 502;
        const detail = e instanceof Upstream ? e.message : `Upstream not reachable: ${e.code ?? e.message}`;
        result = { status, body: JSON.stringify({ status, detail }) };
      }
      const type = result.status >= 400 && !result.body.startsWith('{') ? 'text/plain' : 'application/json';
      res.writeHead(result.status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
      res.end(result.body);
      return;
    }
    serveStatic(staticDir, url.pathname, res);
  });
}

/** A whole number from the query string, kept within [min, max]; the default if missing or not a number. */
function int(params, name, fallback, min, max) {
  const raw = params.get(name);
  if (raw === null || !/^\d+$/.test(raw)) return fallback;
  return Math.min(max, Math.max(min, Number(raw)));
}

class Upstream extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** One HTTP(S) call with a timeout. Resolves with {status, body}; rejects if the upstream is unreachable. */
function request(target, { method = 'GET', headers = {}, body, ca, timeoutMs }) {
  const u = new URL(target);
  const send = u.protocol === 'https:' ? httpsRequest : httpRequest;
  return new Promise((resolvePromise, reject) => {
    const req = send(u, { method, headers: { Accept: 'application/json', ...headers }, ...(ca ? { ca } : {}), timeout: timeoutMs }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => resolvePromise({ status: res.statusCode, body: data }));
    });
    req.on('timeout', () => req.destroy(Object.assign(new Error('timeout'), { code: 'ETIMEDOUT' })));
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

function serveStatic(dir, pathname, res) {
  if (!dir || !existsSync(dir)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Dashboard not built. Run: cd dashboard && npm run build   (or use npm start for development)');
    return;
  }
  const root = resolve(dir);
  let file = normalize(join(root, decodeURIComponent(pathname)));
  if (!file.startsWith(root + sep) && file !== root) { // no ../ escapes
    res.writeHead(403);
    res.end();
    return;
  }
  if (!existsSync(file) || statSync(file).isDirectory()) file = join(root, 'index.html'); // single-page app
  res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' });
  res.end(readFileSync(file));
}

/** Configuration for this repository's stack on your PC (ports of k8s/port-forward.mjs). */
export function localConfig(repoRoot) {
  const site = (x, mgmtPort, gatewayPort) => {
    const dir = join(repoRoot, 'infra', 'wso2', 'generated', x);
    const env = existsSync(join(dir, 'dashboard.env')) ? parseEnv(readFileSync(join(dir, 'dashboard.env'), 'utf8')) : {};
    return {
      tokenUrl: `https://localhost:${mgmtPort}/oauth2/token`,
      gatewayUrl: `https://localhost:${gatewayPort}/branch-${x}/1.0.0`,
      clientId: env.CLIENT_ID,
      clientSecret: env.CLIENT_SECRET,
      scope: env.SCOPE,
      ca: existsSync(join(dir, 'signing-cert.pem')) ? readFileSync(join(dir, 'signing-cert.pem'), 'utf8') : undefined,
    };
  };
  return {
    dcUrl: process.env.DC_URL ?? 'http://localhost:8090',
    sites: { a: site('a', 9444, 8244), b: site('b', 9445, 8245) },
    staticDir: join(repoRoot, 'dashboard', 'dist', 'dashboard', 'browser'),
  };
}

function parseEnv(text) {
  return Object.fromEntries(text.split(/\r?\n/).map((l) => /^([A-Z_]+)=(.*)$/.exec(l)).filter(Boolean).map((m) => [m[1], m[2]]));
}

// Started directly (not imported by the tests)
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const here = dirname(fileURLToPath(import.meta.url));
  const port = Number(process.env.PORT ?? 8095);
  createServer(localConfig(resolve(here, '..'))).listen(port, '127.0.0.1', () => {
    console.log(`Dashboard on http://localhost:${port}`);
  });
}
