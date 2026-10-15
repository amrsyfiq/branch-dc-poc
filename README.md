# Branch–DC Event-Driven POC

A data centre (DC) distributes data to branches through Apache Kafka. Each branch gets **only its own** data, and WSO2 API Manager guards the entry into each branch. The flow is one-way: **DC → Kafka → listener → WSO2 → branch**.

- Plan, decisions and progress: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
- Architecture chart: [docs/architecture-chart.md](docs/architecture-chart.md)

> **Status:** Phases 1–4 are done: the DC, Kafka, the listeners, two branches each behind its own WSO2 gateway, and a live dashboard. Phase 5 (hardening and final docs) is next.

## How it fits together

```text
 DC environment (dc-net)                     Branch A environment (branch-a-net, internal)
┌────────────────────────────────────┐      ┌──────────────────────────────────────────┐
│ dc-service ─► Kafka ─► listener-a ─┼──────┼─► wso2-a ──────► branch-a (BRANCH_A_PDB)  │
│ (DC_PDB)           └─► listener-b ─┼──┐   │    ▲                                     │
└────────────────────────────────────┘  │   └────┼─────────────────────────────────────┘
                                        │      edge-a ◄── your PC (portals, dashboard)
                                        │   Branch B environment (branch-b-net, internal)
                                        │   ┌──────────────────────────────────────────┐
                                        └───┼─► wso2-b ──────► branch-b (BRANCH_B_PDB)  │
                                            └────┼─────────────────────────────────────┘
                                               edge-b ◄── your PC
```

- **Environments are Docker networks.** A container can only reach containers on a network it has joined.
- **The branch networks are `internal`.** Everything in a branch environment, WSO2 included, has no route out at all: not to the DC, not to Kafka, not to the other branch, and not to ports published on your PC.
- **Listeners live in the DC environment, one per branch.** Each one joins `dc-net` to read Kafka, plus **only** its own branch's network to deliver events through that branch's WSO2.
- **Each branch has its own WSO2** (`wso2-a`, `wso2-b`), with its own signing key, users, API, scopes and applications. The branch accepts a request only if it carries a JWT signed by **its own** WSO2.
- **An edge forwarder per branch** (`edge-a`, `edge-b`) is a tiny TCP forwarder. It is the only way from your PC into a branch's WSO2, and it only forwards incoming connections.
- **The branches are the same code.** Branch A and branch B run the same image; only `BRANCH_ID`, the database and the trusted WSO2 certificate differ.

### Who may do what (enforced by WSO2, then checked again by the branch)

| WSO2 application | Owner (WSO2 user and role) | Scope it can get | Allowed |
|---|---|---|---|
| `listener-a` on wso2-a | `listener` (role `branch_sync`) | `branch_a:sync` | `POST /events` into branch A |
| `dashboard-a` on wso2-a | `dashboard` (role `branch_read`) | `branch_a:read` | `GET /data` from branch A |
| (the same for branch B on wso2-b) | | `branch_b:…` | |

WSO2 grants a scope to a client-credentials application only if the application's **owner** has the role bound to that scope. So the dashboard can't get a sync token even if it asks for one.

## Quick start

You need Docker Desktop, JDK 21 and Node 22. The whole stack uses about 6 GB of Docker memory.

```bash
node infra/init-env.mjs              # .env with random passwords (git-ignored; keep it). Re-run after upgrades: adds new keys only
node infra/wso2/init-keys.mjs        # a separate signing key per WSO2 instance (git-ignored)
./mvnw -DskipTests package           # builds the jars
docker compose up -d --build --wait  # everything; WSO2 takes about 2 minutes on first start
node infra/wso2/setup.mjs            # configures both WSO2s: users, roles, API, scopes, applications, keys
docker compose up -d --wait          # restarts the listeners with the client credentials setup.mjs wrote
cd dashboard && npm ci && npm run build && npm run serve   # dashboard: http://localhost:8095
```

Notes:

- **First Oracle start:** it creates the pluggable databases `DC_PDB`, `BRANCH_A_PDB` and `BRANCH_B_PDB` and takes about a minute. See [infra/oracle/01-create-pdbs.sh](infra/oracle/01-create-pdbs.sh).
- **Re-runs:** `setup.mjs` is safe to run again.
- **WSO2 state:** it lives in Docker volumes, so recreating a WSO2 container keeps its configuration.

## Try it

```bash
curl localhost:8090/data              # DC: every branch's products and prices
curl -X POST localhost:8090/sync/A    # send branch A its slice   -> 202 {"status":"PUBLISHED", ...}
curl -X POST localhost:8090/purge/B   # tell branch B to delete its data
curl localhost:8090/sync-log          # latest requests, newest first
```

Read a branch the way the dashboard does: get a token from that branch's WSO2, then call its gateway.

```bash
source infra/wso2/generated/a/dashboard.env
TOKEN=$(curl -s --cacert infra/wso2/generated/a/signing-cert.pem -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=client_credentials&scope=$SCOPE" https://localhost:9444/oauth2/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s --cacert infra/wso2/generated/a/signing-cert.pem -H "Authorization: Bearer $TOKEN" \
  https://localhost:8244/branch-a/1.0.0/data
```

**Postman:** import [postman/branch-dc-poc.postman_collection.json](postman/branch-dc-poc.postman_collection.json). Then run `node scripts/postman-env.mjs`, import the environment it writes (`infra/wso2/generated/branch-dc-poc.postman_environment.json`, git-ignored because it holds the dashboard client secrets) and select it. For HTTPS, add `infra/wso2/generated/wso2-ca.pem` under Settings → Certificates → CA certificates, or switch off SSL certificate verification. The branch folders fetch their WSO2 token automatically. To run the collection without Postman: `npx newman@6.2.2 run postman/branch-dc-poc.postman_collection.json -e infra/wso2/generated/branch-dc-poc.postman_environment.json --ssl-extra-ca-certs infra/wso2/generated/wso2-ca.pem` (note: folder 3 purges both branches).

The WSO2 portals: `https://localhost:9444/publisher` and `/devportal` for branch A, and `:9445` for branch B. Log in as `admin` with `WSO2_ADMIN_PASSWORD` from `.env`. The certificate is self-signed per instance, so the browser will warn.

Or open the **dashboard** at `http://localhost:8095`. It shows the DC next to each branch (read through its WSO2), with Sync and Purge buttons, and whether each branch is in sync. See [dashboard/README.md](dashboard/README.md).

Scripts:

```bash
bash scripts/check-isolation.sh   # network rules between the environments (all must say "ok")
node scripts/check-gateways.mjs   # security matrix: tokens, scopes, foreign keys, bypass attempts (24 checks)
bash scripts/demo-outage.sh       # branch A down during a sync: retries -> dead-letter topic -> recovery
```

Look at the records on a topic:

```bash
docker exec bdc-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9094 \
  --topic dc.branch-a --from-beginning --formatter-property print.key=true --timeout-ms 5000
```

In Git Bash, put `MSYS_NO_PATHCONV=1` in front of `docker exec`. Otherwise Git Bash rewrites `/opt/...` into a Windows path.

## What a branch does with an event

The listener delivers each event with `POST /events`, through the branch's WSO2. The branch then:

| Case | Result | Listener |
|---|---|---|
| New snapshot or purge | `APPLIED`: the snapshot replaces all products; the purge deletes them | done |
| Same event id again (Kafka delivers at least once) | `DUPLICATE`, nothing changes | done |
| Older than the event the current data came from | `STALE`, recorded but not applied | done |
| Event for another branch | 422, nothing stored | straight to the dead-letter topic |
| Gateway rejects the token (401) | | drops the token, retries with a new one |
| Missing scope (403) | | straight to the dead-letter topic |
| Branch or WSO2 down, or 5xx | | retry 8 times over about 2 minutes (1 s, 2 s, 4 s, … up to 30 s), then the dead-letter topic. It is long because WSO2 suspends a failing endpoint for a while |

A dead-lettered event isn't replayed automatically. Sync the branch again: a snapshot is a full replace.

## Tests

```bash
./mvnw test     # needs Docker running: starts a real Oracle and Kafka (Testcontainers)
cd dashboard && npm test -- --watch=false && npm run test:server   # dashboard UI and its proxy
```

Check that the summary says `Skipped: 0`. If Docker isn't running, the integration tests are skipped, not failed.

## Ports (all bound to 127.0.0.1)

| Service | Port on your PC |
|---|---|
| DC service | 8090 |
| WSO2-A: portals and token endpoint, gateway | 9444, 8244 |
| WSO2-B: portals and token endpoint, gateway | 9445, 8245 |
| Oracle | 1531 (`DC_PDB`, `BRANCH_A_PDB`, `BRANCH_B_PDB`) |
| Kafka | 9094 |
| Dashboard (`npm run serve` in `dashboard/`) | 8095 |
| Kafka UI (kafbat), on demand: `docker compose --profile ui up -d kafka-ui` | 8096 |
| Branches, listeners | not published |

## Layout

```text
events/           event contract shared by all services (BranchSyncEvent, ProductPrice)
dc-service/       the DC: GET /data, POST /sync/{branch}, POST /purge/{branch}, GET /sync-log
branch-service/   a branch (same code per branch): GET /data, POST /events, gateway JWT check
branch-listener/  a listener (same code per branch): reads dc.branch-<id>, delivers through the branch's WSO2
dashboard/        Angular 22 dashboard + server.mjs (serves it, holds the read-only WSO2 secrets, forwards fixed API calls)
infra/            init-env.mjs (.env), oracle/ (PDB script), wso2/ (deployment.toml, init-keys.mjs, setup.mjs)
scripts/          check-isolation.sh, check-gateways.mjs, demo-outage.sh
Dockerfile        one image recipe for every Spring Boot module (MODULE build argument)
```
