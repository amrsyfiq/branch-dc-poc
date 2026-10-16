#!/usr/bin/env node
/**
 * Gives each WSO2 instance its own signing key.
 *
 *   node infra/wso2/init-keys.mjs        (before the first  docker compose up)
 *
 * Why: every container of the wso2/wso2am image ships the same private key (alias "wso2carbon",
 * CN=localhost). WSO2 signs its tokens and the backend JWT (X-JWT-Assertion) with it. If both branch
 * gateways used that key, branch A would accept a JWT signed by branch B's WSO2, because the signature
 * would check out. With one key per instance, branch A trusts only WSO2-A.
 *
 * For each instance (a, b) this writes to infra/wso2/generated/<x>/ (git-ignored):
 *   wso2carbon.jks         the image's keystore, with the "wso2carbon" key replaced by a new RSA 2048 key
 *                          (CN=wso2-<x>; valid for the hostnames localhost and wso2-<x>)
 *   client-truststore.jks  the image's truststore, with the "wso2carbon" certificate replaced by the new one
 *   signing-cert.pem       the new certificate: the branch verifies the gateway's JWT with it, and the
 *                          listener uses it to trust the gateway's TLS (HTTPS) certificate
 *
 * keytool runs inside a throwaway container of the WSO2 image, so no local JDK setup is needed.
 * Never overwrites existing keys (tokens and data encrypted with them would become unreadable).
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const IMAGE = 'wso2/wso2am:4.7.0';
const SECURITY = '/home/wso2carbon/wso2am-4.7.0/repository/resources/security';
const PASS = 'wso2carbon'; // WSO2's default keystore password, referenced by deployment.toml (local only)

const here = dirname(fileURLToPath(import.meta.url));

for (const site of ['a', 'b']) {
  const out = join(here, 'generated', site);
  if (existsSync(join(out, 'wso2carbon.jks'))) {
    console.log(`wso2-${site}: keys exist, left unchanged`);
    continue;
  }
  mkdirSync(out, { recursive: true });
  const script = [
    'set -e',
    `cp ${SECURITY}/wso2carbon.jks ${SECURITY}/client-truststore.jks /out/`,
    'cd /out',
    `keytool -delete -alias wso2carbon -keystore wso2carbon.jks -storepass ${PASS}`,
    `keytool -genkeypair -alias wso2carbon -keyalg RSA -keysize 2048 -validity 825 ` +
      `-dname "CN=wso2-${site}, OU=Branch ${site.toUpperCase()} gateway, O=branch-dc-poc" ` +
      `-ext "SAN=dns:localhost,dns:wso2-${site}" ` +
      `-keystore wso2carbon.jks -storepass ${PASS} -keypass ${PASS}`,
    `keytool -exportcert -rfc -alias wso2carbon -keystore wso2carbon.jks -storepass ${PASS} -file signing-cert.pem`,
    `keytool -delete -alias wso2carbon -keystore client-truststore.jks -storepass ${PASS}`,
    `keytool -importcert -noprompt -alias wso2carbon -file signing-cert.pem -keystore client-truststore.jks -storepass ${PASS}`,
  ].join(' && ');
  execFileSync('docker', ['run', '--rm', '--user', 'root', '-v', `${out}:/out`, '--entrypoint', 'sh', IMAGE, '-c', script],
    { stdio: ['ignore', 'ignore', 'inherit'], env: { ...process.env, MSYS_NO_PATHCONV: '1' } });
  console.log(`wso2-${site}: new signing key -> ${join('infra', 'wso2', 'generated', site)}`);
}
