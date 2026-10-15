#!/usr/bin/env node
/**
 * Writes a Postman environment for postman/branch-dc-poc.postman_collection.json, with this machine's
 * read-only WSO2 clients (dashboard-a, dashboard-b) from infra/wso2/generated/ (run infra/wso2/setup.mjs first).
 *
 *   node scripts/postman-env.mjs
 *
 * Output (git-ignored, contains client secrets):
 *   infra/wso2/generated/branch-dc-poc.postman_environment.json   import into Postman and select it
 *   infra/wso2/generated/wso2-ca.pem                               both WSO2 certificates in one file, for
 *                                                                  Postman's Settings → Certificates → CA certificates
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const generated = join(resolve(dirname(fileURLToPath(import.meta.url)), '..'), 'infra', 'wso2', 'generated');

const read = (file) => {
  if (!existsSync(file)) throw new Error(`${file} not found. Run: node infra/wso2/setup.mjs`);
  return readFileSync(file, 'utf8');
};
const client = (site) => Object.fromEntries(read(join(generated, site, 'dashboard.env')).split(/\r?\n/)
  .map((l) => /^([A-Z_]+)=(.*)$/.exec(l)).filter(Boolean).map((m) => [m[1], m[2]]));

const values = [{ key: 'dc_url', value: 'http://localhost:8090', type: 'default' }];
for (const [site, mgmt, gateway] of [['a', 9444, 8244], ['b', 9445, 8245]]) {
  const c = client(site);
  values.push(
    { key: `wso2_${site}_token_url`, value: `https://localhost:${mgmt}/oauth2/token`, type: 'default' },
    { key: `gateway_${site}_url`, value: `https://localhost:${gateway}/branch-${site}/1.0.0`, type: 'default' },
    { key: `dashboard_${site}_client_id`, value: c.CLIENT_ID, type: 'default' },
    { key: `dashboard_${site}_client_secret`, value: c.CLIENT_SECRET, type: 'secret' },
    { key: `dashboard_${site}_scope`, value: c.SCOPE, type: 'default' },
    { key: `token_${site}`, value: '', type: 'secret' },        // filled by the collection's pre-request script
    { key: `token_${site}_expires`, value: '0', type: 'default' },
  );
}

const envFile = join(generated, 'branch-dc-poc.postman_environment.json');
writeFileSync(envFile, `${JSON.stringify({
  name: 'branch-dc-poc (local)',
  values: values.map((v) => ({ ...v, enabled: true })),
  _postman_variable_scope: 'environment',
}, null, 2)}\n`);

const caFile = join(generated, 'wso2-ca.pem');
writeFileSync(caFile, read(join(generated, 'a', 'signing-cert.pem')) + read(join(generated, 'b', 'signing-cert.pem')));

console.log(`Postman environment: ${envFile}`);
console.log(`CA certificates:     ${caFile}`);
