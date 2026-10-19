# Branch–DC Event-Driven POC

A data centre (DC, immigration HQ) distributes approved travel passes to entry checkpoints (the branches) through Apache Kafka. Each checkpoint gets **only its own** travellers' passes, and WSO2 API Manager guards the entry into each checkpoint. The flow is one-way: **DC → Kafka → listener → WSO2 → branch**. All names and passport numbers are fake. It runs on **local Kubernetes** (Docker Desktop).

- Plan, decisions and progress: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
- Architecture chart: [docs/architecture-chart.md](docs/architecture-chart.md)

> **Status:** Phases 1–4 built the DC, Kafka, the listeners, two branches each behind its own WSO2 gateway, and a live dashboard. Part 2 ([plan](IMPLEMENTATION_PLAN.md#part-2-kubernetes-immigration-data-scaling-and-logging)): Phase 5 checked local Kubernetes; Phase 6 switched to the immigration dataset, PostgreSQL, one event per pass and structured logging; **Phase 7 moved everything to Kubernetes** (Docker Compose is gone). Phase 8: the branch service scales on CPU (HPA), the listeners on their Kafka backlog (KEDA) ([scaling guide](docs/k8s-scaling-guide.md)). A ten-minute [scaling demo](docs/k8s-scaling-guide.md#the-demo-k9s) in k9s. The DC sends sync, purge and rush in chunks (memory stays flat however big a checkpoint grows). Next: central logs (Phase 9).

## How it fits together

```text
 namespace dc                                  namespace branch-a
┌───────────────────────────────────────┐     ┌──────────────────────────────────────────────┐
│ dc-service ──► postgres-dc            │     │ wso2-a ──► branch-a ──► postgres-a            │
│     │                                 │     │   ▲                                          │
│     ▼          listener-a ────────────┼─────┼───┘   (only listener A may reach wso2-a)     │
│  (Kafka) ◄──── listener-b ──────┐     │     └──────────────────────────────────────────────┘
└─────┼───────────────────────────┼─────┘     namespace branch-b
 namespace platform: kafka-0      └───────────┼─► wso2-b ──► branch-b ──► postgres-b  (the same) │
                                              └──────────────────────────────────────────────┘
 your PC ── kubectl port-forward (127.0.0.1) ──► dc-service :8090, wso2-a :9444/:8244, wso2-b :9445/:8245
```

- **Environments are namespaces.** Every namespace **denies all traffic by default**, in and out; each NetworkPolicy then opens exactly one path. So a branch has no route to the DC, Kafka, the other branch, your PC or the internet (checked by `scripts/check-isolation.sh`).
- **Listeners live in the DC environment, one per branch.** Each may reach Kafka and **only** its own branch's WSO2.
- **Each branch has its own WSO2** (`wso2-a`, `wso2-b`), with its own signing key, users, API, scopes and applications. The branch accepts a request only if it carries a JWT signed by **its own** WSO2.
- **Each site has its own PostgreSQL**, reachable only by its own service.
- **The branches are the same code and the same manifests** (Kustomize base + an overlay per branch): only the branch id, the names and WSO2's port offset differ.
- **One event per pass, keyed by application number,** on 6 partitions per branch topic: up to 6 listener consumers can deliver to one branch at once, and Kafka keeps the order of each pass's events.
- **From your PC, only through `kubectl port-forward`**, which listens on 127.0.0.1 (a LoadBalancer Service would listen on your Wi-Fi too).

### Who may do what (enforced by WSO2, then checked again by the branch)

| WSO2 application | Owner (WSO2 user and role) | Scope it can get | Allowed |
|---|---|---|---|
| `listener-a` on wso2-a | `listener` (role `branch_sync`) | `branch_a:sync` | `POST /events` into branch A |
| `dashboard-a` on wso2-a | `dashboard` (role `branch_read`) | `branch_a:read` | `GET /data`, `GET /summary`, `POST /passes/search` at branch A |
| (the same for branch B on wso2-b) | | `branch_b:…` | |

WSO2 grants a scope to a client-credentials application only if the application's **owner** has the role bound to that scope. So the dashboard can't get a sync token even if it asks for one.

## Quick start

You need Docker Desktop with **Kubernetes enabled** (Settings → Kubernetes → kind, 1 node), JDK 21 and Node 22. The stack uses about 7 GB of the Docker VM (each WSO2 up to 2 GB).

```bash
node infra/init-env.mjs          # .env with random passwords (git-ignored; keep it). Re-run after upgrades: adds new keys only
node infra/wso2/init-keys.mjs    # a separate signing key per WSO2 instance (git-ignored)
node k8s/deploy.mjs              # builds jars + images (unique tag), creates the Secrets, applies k8s/
kubectl get pods -A -l app.kubernetes.io/part-of=branch-dc-poc -w   # wait until all are 1/1 (WSO2: ~3 minutes)
node k8s/port-forward.mjs        # in another terminal; keep it running
node infra/wso2/setup.mjs        # first time: users, roles, API, scopes, applications, keys on both WSO2s
node k8s/secrets.mjs && kubectl -n dc rollout restart deploy/listener-a deploy/listener-b   # listeners get their keys
cd dashboard && npm ci && npm run build && npm run serve   # dashboard: http://localhost:8095
```

Notes:

- **Redeploy after a code change:** `node k8s/deploy.mjs` (`--keep-parked` leaves a parked branch parked). Every build gets a new image tag (`<commit>-dirty-<time>`): the cluster ignores a rebuilt image under the same tag. `--no-build` reapplies manifests and secrets only.
- **First deploy is slow** (about 6–8 minutes): the node copies each image in once, one at a time (WSO2's alone takes ~3 minutes).
- **Re-runs:** `setup.mjs` and both k8s scripts are safe to run again. WSO2, Kafka and the databases keep their state on PersistentVolumes.
- **Add-ons:** `deploy.mjs` installs metrics-server (CPU for `kubectl top` and the branch's autoscaler) and KEDA (the listeners' autoscaler, on Kafka lag) from `k8s/addons/`.
- **Memory:** close what you can on Windows. With ~1 GB free on the host, everything in the VM slowed down at times (Phase 7 log).

## Try it

```bash
curl localhost:8090/summary                       # DC: per checkpoint, pass counts and the latest request
curl 'localhost:8090/data?branch=A&size=5'       # DC: checkpoint A's passes, newest first (paged)
curl -X POST localhost:8090/sync/A               # send checkpoint A every pass of its slice -> 202 {"status":"PUBLISHED", ...}
curl -X POST 'localhost:8090/rush/A?count=2000'  # holiday rush: 2000 new passes for A, created and sent
curl -X POST localhost:8090/purge/B              # tell checkpoint B to delete its passes
curl localhost:8090/sync-log                     # latest requests, newest first
```

Read a branch the way the dashboard does: get a token from that branch's WSO2, then call its gateway.

```bash
source infra/wso2/generated/a/dashboard.env
TOKEN=$(curl -s --cacert infra/wso2/generated/a/signing-cert.pem -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=client_credentials&scope=$SCOPE" https://localhost:9444/oauth2/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s --cacert infra/wso2/generated/a/signing-cert.pem -H "Authorization: Bearer $TOKEN" \
  https://localhost:8244/branch-a/1.0.0/summary
# the counter check: the passport number goes in the body, never in the URL (gateway access logs)
curl -s --cacert infra/wso2/generated/a/signing-cert.pem -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"passportNo":"H29348960"}' https://localhost:8244/branch-a/1.0.0/passes/search
```

**Postman:** import [postman/branch-dc-poc.postman_collection.json](postman/branch-dc-poc.postman_collection.json). Then run `node scripts/postman-env.mjs`, import the environment it writes (`infra/wso2/generated/branch-dc-poc.postman_environment.json`, git-ignored because it holds the dashboard client secrets) and select it. For HTTPS, add `infra/wso2/generated/wso2-ca.pem` under Settings → Certificates → CA certificates, or switch off SSL certificate verification. The branch folders fetch their WSO2 token automatically. Without Postman: `npx newman@6.2.2 run postman/branch-dc-poc.postman_collection.json -e infra/wso2/generated/branch-dc-poc.postman_environment.json --ssl-extra-ca-certs infra/wso2/generated/wso2-ca.pem` (note: the last folder purges both checkpoints).

The WSO2 portals: `https://localhost:9444/publisher` and `/devportal` for branch A, and `:9445` for branch B. Log in as `admin` with `WSO2_ADMIN_PASSWORD` from `.env`. The certificate is self-signed per instance, so the browser will warn.

Or open the **dashboard** at `http://localhost:8095`. It shows the DC next to each checkpoint (read through its WSO2), with Sync, Rush and Purge buttons, and how far each checkpoint is with the DC's latest request. See [dashboard/README.md](dashboard/README.md).

Scripts:

```bash
bash scripts/check-isolation.sh    # network rules between the namespaces and databases (52 rules, all must say "ok")
node scripts/check-gateways.mjs    # security matrix: tokens, scopes, foreign keys, bypass attempts (32 checks, changes no data)
bash scripts/demo-outage.sh        # branch A down during a rush: retries -> branch back -> every pass arrives, nothing dead-lettered
bash scripts/check-k8s-cluster.sh  # the cluster itself: local images, NetworkPolicy enforcement, host access (17 checks)
```

Kafka UI (kafbat), on demand (~0.4 GB):

```bash
kubectl apply -k k8s/optional/kafka-ui && kubectl -n dc port-forward svc/kafka-ui 8096:8080   # http://localhost:8096
kubectl delete -k k8s/optional/kafka-ui                                                       # when done
```

Scaling lab ([guide](docs/k8s-scaling-guide.md) with exercises and measured numbers; park branch B first for memory):

```bash
node k8s/park.mjs b                         # park branch B (all its pods to 0, data kept); --unpark to start it again
node k8s/reset-data.mjs                     # demo data back to the 40 seeded passes (rushes removed), branches re-synced
node k8s/load-test.mjs                      # counter lookups at A through WSO2-A (k6 inside the cluster), 40/s for 4 min
node k8s/load-test.mjs --rate 150 --hold 3m # every 15 s: copies, the HPA's CPU, CPU per pod; then the scale-down
kubectl get hpa -A -w                       # what the autoscalers decide (branches: 1-3 copies on CPU; listeners: 1-3 on lag)
kubectl get so -n dc                        # KEDA's ScaledObjects: READY, ACTIVE (= events waiting), PAUSED
```

## Everyday Kubernetes commands

In Git Bash, put `MSYS_NO_PATHCONV=1` in front of commands with `/paths` inside the container (`kubectl exec … /opt/…`).

```bash
kubectl get pods -A -l app.kubernetes.io/part-of=branch-dc-poc          # everything, all namespaces
kubectl -n dc logs -l app.kubernetes.io/name=branch-listener --prefix -f  # all listener pods, live, pod name per line
kubectl -n branch-a logs deploy/branch-a --since=10m | grep <traceId>    # one request's passes at branch A
kubectl -n dc annotate so listener-a autoscaling.keda.sh/paused-replicas=3  # hold listener A at 3 (KEDA would undo kubectl scale)
kubectl -n dc annotate so listener-a autoscaling.keda.sh/paused-replicas-   # hand it back to KEDA
kubectl top pods -A --sort-by=memory                                     # memory per pod (needs metrics-server)
kubectl get events -A --sort-by=.lastTimestamp | tail -20                # restarts, failed probes, scheduling
kubectl exec -n platform kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic dc.branch-a --from-beginning --property print.key=true --timeout-ms 5000
```

## What a branch does with an event

An event is one change to one pass: an `UPSERT` (the pass) or a `DELETE`, with a sequence number from the DC (higher = newer). The listener delivers it with `POST /events`, through the branch's WSO2. The branch applies it with one SQL statement, "insert, or update only if newer", so any number of listener and branch pods can deliver at once, in any order:

| Case | Result | Listener |
|---|---|---|
| Newer than what the branch has for that pass | `APPLIED`: the pass is stored; a delete leaves a tombstone without personal data | done |
| Same seq again (Kafka delivers at least once) | `DUPLICATE`, nothing changes | done |
| Older than what the branch has (a late retry, after a rebalance) | `STALE`, ignored: it can't undo a newer change, nor bring a deleted pass back | done |
| Event for another branch | 422, nothing stored | straight to the dead-letter topic |
| Gateway rejects the token (401) | | drops the token, retries with a new one |
| Missing scope (403) | | straight to the dead-letter topic |
| Branch or WSO2 down, or 5xx | | retries (1, 2, 4, 8, 16 s, then every 30 s) for about 32 minutes, one WARN line per attempt; the events wait in Kafka, in order. Then the dead-letter topic |

A dead-lettered event isn't replayed automatically. Sync the branch again: upserts with newer sequence numbers simply overwrite.

## Logs

- **JSON lines** (Elastic Common Schema) with `site`, `service.node.name` (the pod), `traceId`, and fields such as `applicationNo`, `seq`, `partition` and `outcome`. Readable text when you run a service on your machine instead.
- **One line per event**, identified by the application number, **never** by passport number, name or birth date (tests check this, including for malformed requests).
- **A trace id follows each request** from the DC through Kafka (`traceparent` header), the listener and WSO2 to the branch.
- **Listeners log their partitions**: which ones they get and give up when copies come and go.
- Kubernetes keeps a pod's logs only while the pod exists: when a pod is removed (scale-down), its logs go with it. Central logs (Loki + Grafana) come in Phase 9.

## Tests

```bash
./mvnw test     # needs Docker running: starts a real PostgreSQL and Kafka (Testcontainers)
cd dashboard && npm test -- --watch=false && npm run test:server   # dashboard UI and its proxy
cd dashboard && npm run test:fresh                                  # the same, after clearing the build caches
```

Check that the summary says `Skipped: 0`. If Docker isn't running, the integration tests are skipped, not failed. The test containers need about 1 GB of free Docker memory: with the whole stack deployed there may not be enough (measured with the earlier Compose stack); park branch B (`node k8s/park.mjs b`) or turn Kubernetes off first.

## Ports on your PC (through `node k8s/port-forward.mjs`, 127.0.0.1 only)

| Service | Port |
|---|---|
| DC service | 8090 |
| WSO2-A: portals and token endpoint, gateway | 9444, 8244 |
| WSO2-B: portals and token endpoint, gateway | 9445, 8245 |
| PostgreSQL of the DC (database `dc`, user `dc`) | 5433 |
| Dashboard (`npm run serve` in `dashboard/`, not in the cluster) | 8095 |
| Kafka UI (on demand, its own port-forward) | 8096 |
| Branches, listeners, Kafka, the branch databases | not reachable from your PC |

## Leftovers from Docker Compose (Phases 1–6)

The stopped `bdc-*` containers and their volumes are still on your PC: `docker ps -a --filter name=bdc-` and `docker volume ls --filter name=branch-dc_`. The biggest is the old Oracle data (`branch-dc_oracle-data`, 5.5 GB). Nothing uses them any more; remove them when you're sure (`docker rm …`, `docker volume rm …`).

## Layout

```text
events/           event contract shared by all services (PassEvent, TravelPass)
dc-service/       the DC: GET /data, /summary, /sync-log; POST /sync, /purge, /rush/{branch}
branch-service/   a branch (same code per branch): GET /data, /summary; POST /passes/search, /events; gateway JWT check
branch-listener/  a listener (same code per branch): reads dc.branch-<id>, delivers through the branch's WSO2
dashboard/        Angular 22 dashboard + server.mjs (serves it, holds the read-only WSO2 secrets, forwards fixed API calls)
k8s/              Kubernetes: namespaces.yaml, platform/ (Kafka), dc/, listener/{base,a,b}, branch/{base,a,b},
                  optional/{kafka-ui,load-test}, addons/{metrics-server,keda}; deploy.mjs, secrets.mjs, port-forward.mjs, load-test.mjs, park.mjs, reset-data.mjs
infra/            init-env.mjs (.env), wso2/ (deployment.toml, init-keys.mjs, setup.mjs)
docs/             architecture charts and sketch, k8s-scaling-guide.md (the scaling exercises)
scripts/          check-isolation.sh, check-gateways.mjs, demo-outage.sh, check-k8s-cluster.sh, postman-env.mjs
Dockerfile        one image recipe for every Spring Boot module (MODULE build argument)
```
