# Implementation Plan: Branch–DC Event-Driven POC

Status: **Phases 0–3 done** (PRs #1–#3, not merged yet). Next: Phase 4 (Angular dashboard).
Last updated: 2026-10-15

See [CLAUDE.md](CLAUDE.md) for the goal and background, and [docs/architecture-chart.md](docs/architecture-chart.md) for the agreed chart.

## Progress log

| Date | Item | Result |
|---|---|---|
| 2026-10-15 | Phase 0: folder inspected (only `CLAUDE.md` + `docs/`; not a git repo). Docker Desktop **not running** (to start before Phase 1). Open decisions answered (§1) | ✅ |
| 2026-10-15 | Plan reviewed: Angular dashboard, defaults confirmed (§6) | ✅ |
| 2026-10-15 | Old `it-access-portal` containers: already stopped (exited at the Docker restart, no restart policy). Docker: 7.7 GiB | ✅ |
| 2026-10-15 | **Risk check: PDBs in Oracle Free.** Init script creates `DC_PDB`, `BRANCH_A_PDB`, `BRANCH_B_PDB` (own `USERS` tablespace + app user each) on first start. Each user logs in to its own PDB; branch A's password on `BRANCH_B_PDB` and `dc` on `BRANCH_A_PDB` → `ORA-01017`. PDBs reopen after a container restart (`SAVE STATE`). Oracle ~2.1 GB. Fallback not needed | ✅ |
| 2026-10-15 | Phase 1: Maven multi-module (Boot 4.1.1, latest 4.1.x), `events` module, dc-service (JPA + Flyway V1 schema, V2 seed: 2 branches, 10 products, 7 prices for A, 6 for B), topics per branch + DLT from the `BRANCH` table, publish after commit with the send awaited → `PUBLISHED`/`FAILED` in `SYNC_LOG` | ✅ |
| 2026-10-15 | Live check: `/data`, `/sync/a` (7 products on `dc.branch-a`, key `A`), `/purge/B` (`dc.branch-b`), `/sync/Z` → 404. 4 topics, 1 partition each. **Kafka stopped → sync answers after 10 s with `FAILED` + Kafka's error**, no hang | ✅ |
| 2026-10-15 | `mvnw test`: **7 run, 0 failures, 0 skipped** (real Oracle + Kafka). Mutation check: slice of all branches → slice test fails (13 ≠ 7); `@EventListener` instead of after-commit → rollback test fails (record published). Restored, `mvnw clean` | ✅ |
| 2026-10-15 | Docker: one `Dockerfile` (`MODULE` arg, non-root, `-Xmx256m`), dc-service in compose, healthy, ~355 MB. Bug found: healthcheck used `/dev/tcp` under `sh` (dash) → run with `bash -c` | ✅ fixed |
| 2026-10-15 | `.gitattributes`: `*.sh` and `mvnw` forced to LF (with `core.autocrlf=true`, a Windows clone would otherwise break the Oracle init script) | ✅ |
| 2026-10-15 | Phase 1 PR #1 opened; repo `amrsyfiq/branch-dc-poc` (public) created | ✅ |
| 2026-10-15 | Phase 2: branch-service (JPA + Flyway, `GET /data`, `POST /events`; duplicate / stale / wrong-branch rules, `LOCK TABLE` so concurrent deliveries can't interleave) and branch-listener (topic + group per branch, `RestClient` to the branch, retry with back-off → DLT, 4xx / other-branch / bad JSON not retried, DLT keeps the original bytes of unreadable records). **Design change:** one `POST /events` instead of `PUT`/`DELETE /sync` (one envelope for both types; two WSO2 scopes: read for GET, sync for POST) | ✅ |
| 2026-10-15 | Bug: Boot 4 has no auto-configured `RestClient.Builder` without the restclient module → listener failed to start; uses `RestClient.builder()` | ✅ fixed |
| 2026-10-15 | **Security bug found by the isolation check:** branch A could reach the DC's API and Kafka through the ports published on the host (`host.docker.internal:8090`, `:9094`), i.e. read every branch's data. Fix: `branch-a-net` / `branch-b-net` are `internal: true`. Mutation check: without `internal` the script reports 3 broken rules; with it all 21 hold. **Phase 3 consequence:** each WSO2 needs an extra non-internal network for its published ports | ✅ fixed |
| 2026-10-15 | Live: listeners replayed earlier events in order; purge A → A empty, B unchanged; sync A → 7 products. `scripts/demo-outage.sh`: branch A stopped → 1 + 3 retries (1 s, 2 s, 4 s) → DLT with original topic + reason headers → branch back → sync applied. Fixed a give-up log that said `null` (ConnectException without message) | ✅ |
| 2026-10-15 | `mvnw test`: **20 run, 0 failures, 0 skipped** (DC 7, branch 7 on Oracle, listener 6 on Kafka + a fake branch HTTP server). Mutation check: no stale check → stale test fails; 4xx retried → rejected test fails; no listener branch check → other-branch test fails. (My first report filter matched only `<failure>`, not `<error>`, and hid two of these; fixed and re-run.) Restored, `mvnw clean` | ✅ |
| 2026-10-15 | Memory, full Phase 2 stack: ~3.3 GB (Oracle 1.6 GB, Kafka 0.4 GB, 5 apps 220–320 MB each) | ✅ |
| — | Not covered by automated tests: the `LOCK TABLE` under real contention is tested with 2 threads only; replaying a dead-lettered record | ⚠️ open |
| 2026-10-15 | Phase 2 PR #2 opened (stacked on #1) | ✅ |
| 2026-10-15 | Phase 3: **separate signing keys per WSO2.** Checked: every `wso2/wso2am:4.7.0` container ships the same `wso2carbon` key (CN=localhost), so branch A would accept a JWT from WSO2-B. `infra/wso2/init-keys.mjs` copies the image's keystore/truststore and replaces that key per instance (CN=wso2-a / wso2-b, SAN localhost + wso2-x). Verified: each port serves its own certificate (different SHA-256 fingerprints) | ✅ |
| 2026-10-15 | Two WSO2 4.7.0 with port offsets 1 / 2 (`offset = "$env{WSO2_PORT_OFFSET}"`: 9444/8244 and 9445/8245, same inside and outside), heap 1 GB, ~1.5 GB each. Both healthy, no errors in the logs | ✅ |
| 2026-10-15 | `infra/wso2/setup.mjs` (idempotent, per instance): roles `branch_sync` / `branch_read`, users `listener` / `dashboard`, API `/branch-x` (`GET /data` → `branch_x:read`, `POST /events` → `branch_x:sync`), apps `listener-x` / `dashboard-x` owned by those users, client-credentials keys. Verified: asking for both scopes, listener gets only sync, dashboard only read (WSO2 binds scopes to the **owner's** roles). `init-env.mjs` now adds missing keys to an existing `.env` without changing existing values | ✅ |
| 2026-10-15 | Backend JWT: both instances use the same issuer `wso2.org/products/am` (access tokens differ: `https://localhost:9444/oauth2/token` vs `:9445`), so the **signing key is what tells the gateways apart**. Branch: `GatewayJwtFilter` (RS256 only, signature with its own WSO2 cert, expiry, issuer, scope per endpoint; 401/403 problem JSON; `branch.security.mode=wso2` by default). Listener: client-credentials token (cached, renewed 60 s early, dropped on 401), HTTPS trusting **only** its WSO2's certificate with hostname checks | ✅ |
| 2026-10-15 | **Security bug found by probing:** WSO2-A (on a normal network for port publishing) could reach the DC, Kafka and Oracle via `host.docker.internal` and the DC's IP. Binding published ports to 127.0.0.1 did **not** help (Docker Desktop forwards host.docker.internal to the host's loopback). Fix: WSO2 stays on the internal branch network; a tiny inbound TCP forwarder (`edge-a`/`edge-b`, `alpine/socat:1.8.1.1`) is the only container on the edge network. Ports are still bound to 127.0.0.1 (not exposed to the LAN) | ✅ fixed |
| 2026-10-15 | WSO2 state lost on container recreate (H2 inside the container) → volumes for `repository/database` and the search index. First attempt indexed `repository/data` (empty; API lookup still failed with 409 on re-run); the index is `$CARBON_HOME/solr`. Its volume was root-owned (folder not in the image) → `AccessDeniedException` → HTTP 500 on API create; fixed with a one-shot `wso2-volumes` chown container. Verified: remove + recreate both WSO2 containers → `setup.mjs` finds everything ("exists"), matrix still passes | ✅ fixed |
| 2026-10-15 | `scripts/check-gateways.mjs`: **24/24 pass**, incl. listener vs dashboard scopes (200/403), no/garbage/other-gateway token 401, and straight to the branch (inside its network): no JWT 401, **JWT signed with the other WSO2's key 401**, own key 200 (control), own key + read scope on POST 403, expired 401. `check-isolation.sh`: **36 rules hold** (added: WSO2 cannot reach DC/Kafka/host; listener reaches only its own WSO2). Live: purge/sync A, sync B through both gateways | ✅ |
| 2026-10-15 | Tests: verifier unit tests (own key, other key, `alg:none`, HS256 with the public key, expiry with 30 s skew, issuer, garbage), web slice in `wso2` mode (401 without JWT, sync vs read scopes, branch B scope or WSO2-B key into A), listener (bearer sent, client credentials + scope on the token call, 401 → new token, token cached). Mutation check: no signature check → other-key test fails; no scope check → 3 web tests fail; token kept after 401 → new-token test fails. Restored, `mvnw clean` | ✅ |
| 2026-10-15 | Memory, full stack with 2 WSO2: ~6.1 GB of 7.7 GB | ✅ |
| 2026-10-15 | `mvnw clean package`: **31 run, 0 failures, 0 skipped** (DC 7, branch 16, listener 8) | ✅ |
| 2026-10-15 | **Reliability issue found in the outage demo:** after one failed call, WSO2 **suspends the endpoint** and answers 500 ("State: SUSPENDED") without trying the branch. Measured: still 500 for ~13 s after branch A was healthy again (~45 s after its restart). With 3 retries over ~7 s, even a short branch blip dead-lettered events although the branch was back. Fix: listener retries 8 times over ~2 min (1, 2, 4, 8, 16, 30, 30, 30 s); events wait in Kafka, in order. Verified: branch A stopped for 3 s during a sync → delivered on the 6th attempt (~31 s), nothing dead-lettered | ✅ fixed |
| — | Alternative not tried: switching off endpoint suspension in WSO2's advanced endpoint config (suspension protects backends from overload; with one listener per branch there is little to protect) | ⚠️ open |

---

## 1. Decisions

| # | Decision | Choice | Source |
|---|---|---|---|
| 1 | WSO2 per branch | **Two real WSO2 instances**, one in each branch environment (`wso2-a`, `wso2-b`) | User |
| 2 | Data | **Products + branch prices** | User |
| 3 | Database | **Oracle Free 23ai, one container, one PDB per site** (`DC_PDB`, `BRANCH_A_PDB`, `BRANCH_B_PDB`) to fit in memory | User |
| 4 | UI | **Small live dashboard**: DC, branch A and branch B side by side, Sync/Purge buttons | User |
| 5 | Sync semantics | **Full replace** of the branch's slice on every sync (simple, idempotent) | User (confirmed) |
| 6 | Number of branches | **2** (A, B); adding a branch = config + compose entries, no code | User (confirmed) |
| 7 | GitHub | New repo **`amrsyfiq/branch-dc-poc`**, public like the previous one; created at the end of Phase 1 | User (confirmed) |
| 8 | Persistence | Spring Data JPA (Hibernate) + Flyway, DTOs in the API, no MyBatis | CLAUDE.md |
| 9 | Dashboard tech | **Angular 22** (like `it-access-portal`), polling every 2 s. Browsers can't keep client secrets, so a **tiny Node proxy** (`dashboard/server.mjs`, no dependencies) holds the read-only WSO2 client secrets, fetches tokens and serves the built app; in development the Angular dev server proxies `/api` to it | User |

### Simplifications (stated, not hidden)

- **One Kafka for all sites.** A real multi-site setup usually has a Kafka cluster per site, replicated with MirrorMaker 2.
- **One Oracle server for all sites.** Each site has its own PDB and user, so the data is separate, but the server and its memory are shared. Branch containers can reach the Oracle container on the network; they only lack the other PDBs' credentials.
- **No outbox.** The DC publishes after the DB commit; if the DC dies between commit and send, that event is lost. The sync log row shows it as `PENDING`, and you can simply sync again (full replace).
- **The dashboard is an observer outside all environments.** It reads the DC directly and each branch **through that branch's WSO2**, with a read-only client.

## 2. Architecture in detail

```text
 host (your PC)                                   ── docker networks ──
 ┌────────────┐
 │ dashboard  │──► DC :8090 (GET /data, POST /sync, /purge, GET /sync-log)
 │ Node :8095 │──► wso2-a gateway :8244 ─┐  (read-only client, scope branch_a:read)
 └────────────┘──► wso2-b gateway :8245 ─┼┐
                                         ││
 dc-net:        dc-service, kafka, oracle, listener-a, listener-b
 branch-a-net:  listener-a, wso2-a, branch-a, oracle
 branch-b-net:  listener-b, wso2-b, branch-b, oracle
```

- **dc-service** (Spring Boot): owns `DC_PDB`. `GET /data` returns all branches. `POST /sync/{branch}` reads that branch's slice and publishes a `SNAPSHOT` event to `dc.branch-{x}`. `POST /purge/{branch}` publishes a `PURGE` event. Each request writes a `SYNC_LOG` row; the event is published **after commit** (`@TransactionalEventListener(AFTER_COMMIT)`). Unknown branch → 404.
- **branch-listener** (Spring Boot, **same code ×2**, configured with `BRANCH_ID`, topic, gateway URL, client id/secret): consumes only its topic, gets a **client-credentials** token from its branch's WSO2 (cached until expiry), and calls the branch through the gateway. Failures: `DefaultErrorHandler` retries with backoff, then `dc.branch-{x}.DLT`. Joins `dc-net` + its own branch net only.
- **branch-service** (Spring Boot, **same code ×2**, configured with `BRANCH_ID` + its own PDB): `GET /data` (its own products), `PUT /sync` (replace all products with the snapshot) and `DELETE /sync` (purge). Only accepts requests carrying a valid JWT from **its own** WSO2 (`X-JWT-Assertion`, RS256, issuer + expiry + signature checked against that WSO2's certificate). **Not published on the host**: the only way in is through WSO2.
- **wso2-a / wso2-b** (WSO2 APIM 4.7.0): one API each (`/branch-a/1.0.0`, `/branch-b/1.0.0`), scopes `branch_x:sync` (PUT/DELETE) and `branch_x:read` (GET). Two applications each: `listener-x` (sync scope) and `dashboard-x` (read scope). Configured by an idempotent `infra/wso2/setup.mjs`.
- **Branches cannot reach the DC**: `branch-a` is only on `branch-a-net`; there's no route to `dc-service` or `kafka`. This is tested (Phase 2).

### Events (JSON, topic per branch, key = branch id, 1 partition per topic)

```json
{ "eventId": "uuid", "type": "SNAPSHOT", "branchId": "A", "occurredAt": "2026-10-15T10:00:00Z",
  "products": [ { "sku": "P-001", "name": "Coffee 1kg", "price": 42.50, "currency": "MYR" } ] }
{ "eventId": "uuid", "type": "PURGE", "branchId": "A", "occurredAt": "...", "products": [] }
```

- **Idempotency:** the branch records applied `eventId`s (unique constraint) and ignores duplicates. It also ignores an event older than the last one it applied (`occurredAt`), so a late retry can't overwrite newer data.
- **Defence in depth:** the branch rejects an event whose `branchId` is not its own, even with a valid token.
- **Size:** the whole slice travels in one record. That's fine for the seed (tens of products), but Kafka's default limit is 1 MB. A real system would page it or send changes only.

### Schema (Flyway)

- **DC_PDB:** `BRANCH(code, name)`, `PRODUCT(sku, name)`, `BRANCH_PRICE(branch_code, sku, price, currency)`, `SYNC_LOG(event_id, branch_code, type, product_count, status, created_at)`. Seed in `V2__seed.sql`: branches A and B, ~10 products, with some sold only in A, some only in B and some in both at different prices.
- **BRANCH_x_PDB:** `BRANCH_PRODUCT(sku, name, price, currency, synced_at)`, `APPLIED_EVENT(event_id, type, occurred_at, applied_at)`.

### Isolation: what enforces it

| Threat | Enforced by |
|---|---|
| Listener A sends to branch B | Listener A is not on `branch-b-net` (can't reach it); its client is subscribed only in **wso2-a** |
| Token from wso2-b used at branch A | Branch A trusts only wso2-a's certificate and issuer. **The two WSO2s get different signing keystores** (the default image ships the same `wso2carbon` key in every instance, which would make this check useless) |
| Dashboard writes to a branch | The dashboard client has the `read` scope only → 403 at the gateway |
| Calling a branch without WSO2 | The branch isn't published on the host, and it rejects requests without a valid JWT (401) |
| Branch calls the DC | No network route |
| Listener A reads topic B | Optional Phase 5: Kafka SASL + ACLs per listener. Until then it's configuration only, and stated as such |

## 3. Stack and versions

Reusing what was verified in `it-access-portal` (2026-10-13/14); I'll check for newer patch versions at the start of Phase 1.

| Component | Version |
|---|---|
| Java / build | Temurin 21.0.12, Maven wrapper, **multi-module** (`events`, `dc-service`, `branch-service`, `branch-listener`) |
| Spring Boot | 4.1.1 (Spring Kafka 4.1.1, Kafka client 4.2.x) |
| Persistence | Spring Data JPA, Flyway (+ `flyway-database-oracle`), `ojdbc11` |
| Oracle | `gvenzl/oracle-free:23-slim-faststart`, PDBs created by an init script on first start |
| Kafka | `apache/kafka:4.2.2`, KRaft single node, auto-create off |
| WSO2 | `wso2/wso2am:4.7.0` ×2 |
| JWT verification | nimbus-jose-jwt |
| Tests | JUnit 6 + Testcontainers (Oracle, Kafka), MockWebServer/WireMock for the gateway in listener tests |
| Dashboard | Angular 22 (standalone, zoneless, Vitest) + Node 22 proxy (no dependencies) |
| Package root | `com.branchdc` (`.events`, `.dc`, `.branch`, `.listener`) |

### Ports (chosen so they don't clash with `it-access-portal`)

| Service | Host port |
|---|---|
| Oracle | 1531 |
| Kafka | 9094 |
| dc-service | 8090 |
| wso2-a: portals/token, gateway | 9444, 8244 |
| wso2-b: portals/token, gateway | 9445, 8245 |
| dashboard | 8095 |
| Kafka UI (optional profile) | 8096 |
| branch-a, branch-b, listeners | **not published** |

### Memory budget (Docker has ~8 GB)

| Item | Estimate |
|---|---|
| Oracle (1 container, 3 PDBs) | ~2.2 GB |
| wso2-a + wso2-b (heap capped ~1 GB each) | ~2.8–3.2 GB |
| Kafka | ~0.6 GB |
| 5 Spring Boot apps (`-Xmx256m`) | ~1.5–1.8 GB |
| **Total** | **~7.1–7.8 GB** |

This is tight. The old project's containers **must be stopped**, and I recommend raising WSL2's limit to **10 GB** (`%UserProfile%\.wslconfig` → `[wsl2] memory=10GB`, then `wsl --shutdown`). I won't change that file without asking you. Compose profiles let you run without WSO2 during Phases 1–2.

## 4. Phases

Each phase is one branch + PR. Every exit criterion is checked by actually running it; skipped tests are reported.

### Phase 1: Repo, infrastructure, DC service
- `git init`, `.gitignore`, `.env.example`, `infra/init-env.mjs` (random secrets, never overwrites).
- Compose: three networks; Oracle with an init script creating the 3 PDBs + users (verify Oracle Free allows it; **fallback = a schema per site**, and I'll tell you if that's needed); Kafka.
- Maven multi-module skeleton; `events` module (event record + types).
- dc-service: Flyway V1 schema + V2 seed, JPA entities → DTOs, `GET /data`, `POST /sync/{branch}`, `POST /purge/{branch}`, `GET /sync-log`, `NewTopic` beans for `dc.branch-a`, `dc.branch-b` and their DLTs, publish after commit.
- Dockerfile (one, parameterised by module) and image builds.
- **Exit:** `mvnw test` (Testcontainers Oracle + Kafka) passes with `Skipped: 0`. Tests cover: slice contains only that branch's rows, sync publishes exactly one record with key = branch, purge event, rollback publishes nothing, unknown branch 404. Then a live check with `kafka-console-consumer`. Create the GitHub repo and open the PR.

### Phase 2: Branch service + listeners (no WSO2 yet)
- branch-service: own PDB, `GET /data`, `PUT/DELETE /sync`, idempotency + stale-event check + branchId check. A `security.mode=none|wso2` switch, with `none` used only in this phase and in tests.
- branch-listener: `@KafkaListener` on its topic, HTTP client to the branch (direct in this phase), retries → DLT.
- Compose: `branch-a`, `branch-b`, `listener-a`, `listener-b` with the network layout from §2.
- **Exit:** tests pass (`Skipped: 0`): duplicate event applied once, older event ignored, wrong branchId rejected, branch down → retries → DLT. Live: sync A → only A has A's products; B is unchanged; purge A → A empty. **Network check:** from inside `branch-a`, `dc-service` and `kafka` don't resolve / connect; `listener-a` cannot reach `branch-b`.

### Phase 3: Two WSO2 gateways
- `wso2-a`, `wso2-b` in their branch networks, own `deployment.toml` each (encryption key from `.env`, **distinct issuer and distinct keystore** generated with `keytool` by an init script).
- `infra/wso2/setup.mjs`: idempotent, run against each instance. It creates the API, scopes and applications; saves client secrets on the first run only (WSO2 shows them once); and exports each instance's certificate for its branch.
- Listener: client-credentials token, cached, refreshed on 401. Branch: `security.mode=wso2` (nimbus, RS256 only).
- **Exit:** security matrix by script: listener-a token → wso2-a PUT 200; dashboard-a token PUT → 403; no token → 401; wso2-b token at wso2-a → 401; a JWT signed by wso2-b sent straight to branch-a (from inside the network) → 401; no JWT → 401. Verifier unit tests (expired, foreign key, `alg:none`, wrong issuer) plus a **mutation check** (skip signature check → tests fail), then `mvnw clean`. Delete and recreate both WSO2 containers, re-run `setup.mjs` → same results.

### Phase 4: Live dashboard
- `dashboard/` Angular 22 app; `dashboard/server.mjs` serves the built app and proxies `/api/dc/*` to the DC and `/api/branch-a|b/data` to the WSO2 gateways using the read-only clients (secrets stay server-side).
- Angular tests (Vitest): columns render per site, Sync/Purge call the right endpoint, error state.
- Page: three columns (DC grouped by branch / Branch A / Branch B), Sync and Purge buttons per branch, sync log with status, polling every 2 s, readable at phone width.
- **Exit:** a real browser (Edge) walkthrough: sync A → A's column fills within a few seconds, B unchanged; purge → empties; no console errors.

### Phase 5: Hardening and docs
- Optional (ask then): Kafka SASL/SCRAM + ACLs, so `listener-a` can only read `dc.branch-a`, and the DC is the only writer.
- `README.md` (quick start, demo script), `docs/architecture.md` (diagrams, decisions, simplifications, what production would add: outbox, Kafka per site + MirrorMaker 2, mTLS, deltas instead of snapshots).
- `scripts/demo.mjs`: sync/purge and print DC vs branch data.
- **Exit:** fresh clone → `init-env` → `docker compose up` → `setup.mjs` → demo works; `mvnw test` 0 failures, 0 skipped.

## 5. Risks to check early

| Risk | Plan |
|---|---|
| Oracle Free PDB creation in the faststart image (limits, init-script timing) | Verify first thing in Phase 1; fallback = schema per site |
| Memory with 2× WSO2 + Oracle | Stop old containers; cap heaps; ask about `.wslconfig` before Phase 3 |
| Replacing WSO2's primary keystore in 4.7 | Try in Phase 3; if it fails, fall back to a distinct **issuer** check + `aud`/client-id check, and say clearly that the keys are shared |
| WSO2 gateway → branch over Docker DNS (backend URL `http://branch-a:8080`) | Simple, but verify the gateway doesn't rewrite headers we rely on |

## 6. Review outcome (2026-10-15)

Full replace, 2 branches and public repo `amrsyfiq/branch-dc-poc`: confirmed. Dashboard: Angular. Old `it-access-portal` containers: stop before Phase 1.
