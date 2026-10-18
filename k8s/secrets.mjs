#!/usr/bin/env node
/**
 * Creates or updates the Secrets and ConfigMaps the manifests expect, from the git-ignored .env and
 * infra/wso2/generated/ (nothing secret is ever in git or in the manifests).
 *
 *   node k8s/secrets.mjs        (k8s/deploy.mjs runs it too)
 *
 *   dc         db (password)                          listener-a-client, listener-b-client (CLIENT_ID,
 *              gateway-cert-a, gateway-cert-b         CLIENT_SECRET, SCOPE; once infra/wso2/setup.mjs has run)
 *   branch-x   db (password), gateway-cert, wso2 (admin-password, encryption-key),
 *              wso2-keystores (this WSO2's own signing key), ConfigMap wso2-config (deployment.toml),
 *              counter-client (the load test's read-only client; once infra/wso2/setup.mjs has run)
 *
 * The objects are piped to  kubectl apply -f -  as JSON on stdin: values never appear on a command line or in a
 * file. Only for the local cluster: refuses any kubectl context other than docker-desktop.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const generated = join(root, 'infra', 'wso2', 'generated');

export function requireLocalCluster() {
  const context = execFileSync('kubectl', ['config', 'current-context'], { encoding: 'utf8' }).trim();
  if (context !== 'docker-desktop') {
    throw new Error(`kubectl context is "${context}", expected "docker-desktop" (this POC only deploys to the local cluster)`);
  }
}

export function applySecrets() {
  requireLocalCluster();
  // The namespaces (and their quotas) first: Secrets live in them.
  execFileSync('kubectl', ['apply', '-f', join(root, 'k8s', 'namespaces.yaml')], { stdio: ['ignore', 'ignore', 'inherit'] });
  const env = dotEnv(join(root, '.env'));
  const need = (key) => {
    if (!env[key]) throw new Error(`${key} missing from .env (run node infra/init-env.mjs)`);
    return env[key];
  };
  const file = (path) => {
    if (!existsSync(path)) throw new Error(`${path} not found (run node infra/wso2/init-keys.mjs)`);
    return readFileSync(path);
  };

  const items = [secret('dc', 'db', { password: need('DC_DB_PASSWORD') })];
  const listeners = [];
  for (const x of ['a', 'b']) {
    const dir = join(generated, x);
    const cert = file(join(dir, 'signing-cert.pem'));
    items.push(
      secret('dc', `gateway-cert-${x}`, { 'signing-cert.pem': cert }),
      secret(`branch-${x}`, 'db', { password: need(`BRANCH_${x.toUpperCase()}_DB_PASSWORD`) }),
      secret(`branch-${x}`, 'gateway-cert', { 'signing-cert.pem': cert }),
      secret(`branch-${x}`, 'wso2', { 'admin-password': need('WSO2_ADMIN_PASSWORD'), 'encryption-key': need('WSO2_ENCRYPTION_KEY') }),
      secret(`branch-${x}`, 'wso2-keystores', {
        'wso2carbon.jks': file(join(dir, 'wso2carbon.jks')),
        'client-truststore.jks': file(join(dir, 'client-truststore.jks')),
      }),
      configMap(`branch-${x}`, 'wso2-config', { 'deployment.toml': readFileSync(join(root, 'infra', 'wso2', 'deployment.toml'), 'utf8') }),
    );
    const listenerEnv = join(dir, 'listener.env');
    if (existsSync(listenerEnv)) {
      const c = dotEnv(listenerEnv);
      items.push(secret('dc', `listener-${x}-client`, { CLIENT_ID: c.CLIENT_ID, CLIENT_SECRET: c.CLIENT_SECRET, SCOPE: c.SCOPE }));
      listeners.push(x);
    }
    const counterEnv = join(dir, 'counter.env');
    if (existsSync(counterEnv)) {
      const c = dotEnv(counterEnv);
      items.push(secret(`branch-${x}`, 'counter-client', { CLIENT_ID: c.CLIENT_ID, CLIENT_SECRET: c.CLIENT_SECRET, SCOPE: c.SCOPE }));
    }
  }
  // Server-side apply: a client-side apply keeps a full copy of each object in an annotation, i.e. a second copy
  // of every secret value, and the WSO2 truststore alone is larger than an annotation may be (256 KB).
  execFileSync('kubectl', ['apply', '--server-side', '--force-conflicts', '--field-manager=branch-dc-secrets', '-f', '-'], {
    input: JSON.stringify({ apiVersion: 'v1', kind: 'List', items }),
    stdio: ['pipe', 'ignore', 'inherit'],
  });
  console.log(`Secrets and ConfigMaps applied (${items.length}); listener credentials for: ${listeners.join(', ') || 'none yet (run infra/wso2/setup.mjs)'}`);
  return listeners;
}

function secret(namespace, name, data) {
  return {
    apiVersion: 'v1', kind: 'Secret', type: 'Opaque',
    metadata: { name, namespace, labels: { 'app.kubernetes.io/part-of': 'branch-dc-poc' } },
    data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, Buffer.from(v).toString('base64')])),
  };
}

function configMap(namespace, name, data) {
  return { apiVersion: 'v1', kind: 'ConfigMap', metadata: { name, namespace, labels: { 'app.kubernetes.io/part-of': 'branch-dc-poc' } }, data };
}

function dotEnv(path) {
  if (!existsSync(path)) throw new Error(`${path} not found`);
  return Object.fromEntries(readFileSync(path, 'utf8').split(/\r?\n/)
    .map((l) => /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(l)).filter(Boolean).map((m) => [m[1], m[2]]));
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  applySecrets();
}
