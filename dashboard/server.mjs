#!/usr/bin/env node
/**
 * The dashboard's small backend ("backend for frontend"). Serves the built Angular app and forwards a
 * fixed list of API calls. Browsers cannot keep secrets, so the WSO2 client secrets (read-only
 * "dashboard-x" applications) stay here and the browser never sees a token.
 *
 *   node dashboard/server.mjs          -> http://localhost:8095   (after: cd dashboard && npm run build)
 *
 * Routes (nothing else is forwarded, so this is not an open proxy):
 *   GET  /api/dc/data              -> DC  GET  /data
 *   GET  /api/dc/sync-log?limit=n  -> DC  GET  /sync-log?limit=n
 *   POST /api/dc/sync/{a|b}        -> DC  POST /sync/{branch}
 *   POST /api/dc/purge/{a|b}       -> DC  POST /purge/{branch}
 *   GET  /api/branches/{a|b}/data  -> branch x's WSO2 gateway GET /branch-x/1.0.0/data, with a
 *                                     client-credentials token (scope branch_x:read) from that same WSO2
 *
 * Reads infra/wso2/generated/<x>/dashboard.env and signing-cert.pem (written by infra/wso2/setup.mjs and
 * init-keys.mjs). HTTPS to WSO2 trusts only that instance's certificate.
 */
import { existsSync, readFileSync, statSync } from 'node:fs';
import { createServer as createHttpServer, request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TIMEOUT_MS = 5000;
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
 */
export function createServer({ dcUrl, sites, staticDir }) {
  const tokens = {}; // site -> { value, renewAt }

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

  async function branchData(site) {
    const s = sites[site];
    if (!s.clientId || !s.clientSecret) {
      throw new Upstream(503, `No WSO2 client for branch ${site.toUpperCase()}. Run: node infra/wso2/setup.mjs`);
    }
    const get = async () => call(`${s.gatewayUrl}/data`, { ca: s.ca, headers: { Authorization: `Bearer ${await token(site)}` } });
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
    if (req.method === 'GET' && path === '/api/dc/data') return call(`${dcUrl}/data`);
    if (req.method === 'GET' && path === '/api/dc/sync-log') {
      const limit = Number.parseInt(url.searchParams.get('limit') ?? '20', 10);
      return call(`${dcUrl}/sync-log?limit=${Number.isFinite(limit) ? limit : 20}`);
    }
    if (req.method === 'POST' && (m = /^\/api\/dc\/(sync|purge)\/([a-z])$/i.exec(path)) && sites[m[2].toLowerCase()]) {
      return call(`${dcUrl}/${m[1].toLowerCase()}/${m[2].toUpperCase()}`, { method: 'POST' });
    }
    if (req.method === 'GET' && (m = /^\/api\/branches\/([a-z])\/data$/i.exec(path)) && sites[m[1].toLowerCase()]) {
      return branchData(m[1].toLowerCase());
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

class Upstream extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** One HTTP(S) call with a timeout. Resolves with {status, body}; rejects if the upstream is unreachable. */
function call(target, { method = 'GET', headers = {}, body, ca } = {}) {
  const u = new URL(target);
  const send = u.protocol === 'https:' ? httpsRequest : httpRequest;
  return new Promise((resolvePromise, reject) => {
    const req = send(u, { method, headers: { Accept: 'application/json', ...headers }, ...(ca ? { ca } : {}), timeout: TIMEOUT_MS }, (res) => {
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

/** Configuration for this repository's compose stack (ports from docker-compose.yml). */
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
