#!/usr/bin/env node
/**
 * Builds and deploys the POC to the local Kubernetes cluster (Docker Desktop):
 *
 *   node k8s/deploy.mjs               build jars + images, apply secrets and manifests
 *   node k8s/deploy.mjs --no-build    reuse the images of the last build (manifest or secret changes only)
 *
 * 1. ./mvnw -DskipTests package, then one image per Spring Boot module, tagged <git commit>[-dirty-<time>].
 *    A unique tag on every build: the cluster ignores a rebuilt image under the same tag (Phase 5 finding), so
 *    "latest" would silently keep running the old code. No registry: Docker Desktop's cluster pulls local images.
 * 2. k8s/secrets.mjs: Secrets and ConfigMaps from .env and infra/wso2/generated/.
 * 3. The add-ons the manifests need: metrics-server (CPU for the branch's HPA) and KEDA (the listeners'
 *    ScaledObjects). Re-applying them is a no-op.
 * 4. kubectl kustomize k8s, with the image tags filled in, piped to kubectl apply.
 * 5. A parked branch (k8s/park.mjs) is started again: after a deploy everything runs.
 */
import { execFileSync, execSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isParked, unpark } from './park.mjs';
import { applySecrets, requireLocalCluster } from './secrets.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MODULES = ['dc-service', 'branch-service', 'branch-listener'];
const lastTagFile = join(root, 'k8s', '.last-image-tag'); // git-ignored
const build = !process.argv.includes('--no-build');

requireLocalCluster();

let tag;
if (build) {
  const commit = git('rev-parse', '--short', 'HEAD');
  const dirty = git('status', '--porcelain') !== '';
  tag = dirty ? `${commit}-dirty-${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}` : commit;
  step(`Building jars and images (tag ${tag})`);
  const mvnw = process.platform === 'win32' ? `"${join(root, 'mvnw.cmd')}"` : './mvnw';
  execSync(`${mvnw} -q -DskipTests package`, { cwd: root, stdio: 'inherit' });
  for (const module of MODULES) {
    execFileSync('docker', ['build', '-q', '--build-arg', `MODULE=${module}`, '-t', `branch-dc/${module}:${tag}`, '.'],
      { cwd: root, stdio: ['ignore', 'ignore', 'inherit'] });
    console.log(`   branch-dc/${module}:${tag}`);
  }
  writeFileSync(lastTagFile, `${tag}\n`);
} else {
  if (!existsSync(lastTagFile)) throw new Error('No earlier build: run without --no-build first');
  tag = readFileSync(lastTagFile, 'utf8').trim();
  step(`Reusing images ${tag}`);
}

step('Secrets');
applySecrets();

step('Add-ons (metrics-server, KEDA)');
execFileSync('kubectl', ['apply', '-k', join(root, 'k8s', 'addons', 'metrics-server')], { stdio: ['ignore', 'ignore', 'inherit'] });
// Server-side: KEDA's CRDs are too big for the annotation a client-side apply keeps.
execFileSync('kubectl', ['apply', '--server-side', '-k', join(root, 'k8s', 'addons', 'keda')], { stdio: ['ignore', 'ignore', 'inherit'] });
execFileSync('kubectl', ['wait', '--for=condition=Established', 'crd/scaledobjects.keda.sh', '--timeout=60s'], { stdio: ['ignore', 'ignore', 'inherit'] });
console.log('   applied');

step('Manifests');
let manifests = execFileSync('kubectl', ['kustomize', join(root, 'k8s')], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
for (const module of MODULES) {
  manifests = manifests.replaceAll(`image: branch-dc/${module}:latest`, `image: branch-dc/${module}:${tag}`);
}
if (manifests.includes(':latest')) throw new Error('A manifest still refers to a :latest image');
execFileSync('kubectl', ['apply', '-f', '-'], { input: manifests, stdio: ['pipe', 'inherit', 'inherit'] });

for (const x of ['a', 'b']) {
  if (isParked(x)) {
    step(`Branch ${x.toUpperCase()} was parked: starting it again (WSO2 needs 2-3 minutes; its listener retries meanwhile)`);
    await unpark(x, { wait: false });
  }
}

step('Next');
console.log(`   kubectl get pods -A -l app.kubernetes.io/part-of=branch-dc-poc -w     (WSO2 needs about 2-3 minutes)
   node k8s/port-forward.mjs                                              (in another terminal; keep it running)
   node infra/wso2/setup.mjs                                              (first time, once both WSO2 are ready)
   node k8s/secrets.mjs && kubectl -n dc rollout restart deploy/listener-a deploy/listener-b`);

function git(...args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
}

function step(title) {
  console.log(`\n== ${title}`);
}
