# Implementation Plan: Branch–DC Event-Driven POC

Status: **Phases 0–4 done** (PRs #1–#4 merged into `main` on 2026-10-16). **Part 2 (Kubernetes, immigration data, scaling, logging) is planned and reviewed (decisions in §P2.10)**: see [Part 2](#part-2-kubernetes-immigration-data-scaling-and-logging). It replaces the old Phase 5. **Phases 5 (Kubernetes check), 6 (immigration data, scalable code, logging) and 7 (on Kubernetes) done; Phase 8a (branch service on CPU) and the scaling demo done, Phase 8b (listener on Kafka lag, KEDA) done, the DC's sync in chunks (it ran out of memory) fixed; next: Phase 9.**
Last updated: 2026-10-16

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
| 2026-10-15 | Phase 3 PR #3 opened (stacked on #2) | ✅ |
| 2026-10-15 | Phase 4: Angular 22.2 app (`npx @angular/cli@22 new`, zoneless, standalone, Vitest) in `dashboard/`. `server.mjs` (Node, no dependencies): serves the build, forwards **only** 5 routes, holds the read-only `dashboard-x` WSO2 secrets, caches tokens, new token once on 401, trusts only each WSO2's certificate, blocks `../` paths. The browser never sees a secret or token | ✅ |
| 2026-10-15 | UI: DC (per-branch slice + Sync/Purge) next to Branch A and B (read through their WSO2), badge per branch (In sync / Not synced / Differs with highlighted rows / Unreachable), last event, sync log; every panel loads on its own, polling every 2 s without overlapping requests. `ng build` clean (51 kB transferred) | ✅ |
| 2026-10-15 | Tests: Angular **11 pass** (comparison logic; panels, differences, one branch unreachable, Sync/Purge calls + notices, FAILED publish); proxy **6 pass** (`node --test`, fake upstreams: allowed routes only, Basic credentials + scope on the token call, token cached, 401 → new token, 403 passed through, 503 without setup, 502 when unreachable, static files only from the build). Mutation check: price ignored in the comparison → 2 tests fail; branch allow-list removed → "not an open proxy" test fails (502 ≠ 404). (The first proxy mutation silently did not apply because of `sed` escaping; redone with a script.) Restored | ✅ |
| 2026-10-15 | Browser (headless Edge via Playwright, real stack): Purge A → A "Not synced" in ~0.5 s, B unchanged; Sync A → "In sync" in ~0.6 s; sync log updated; 390 px wide: no horizontal scroll; dark mode readable; no console errors. Branch A stopped → A "Unreachable: HTTP 500: Error connecting to the back end", B and DC keep updating | ✅ |
| 2026-10-15 | User request: Kafka UI (kafbat v1.5.0) now part of the default stack (was behind `--profile ui`), heap 256 MB (~415 MB), health check, `127.0.0.1:8096`. Verified via its API and in Edge: cluster ONLINE, 4 branch topics (dc.branch-a.DLT holds the 2 outage-demo events), groups listener-a / listener-b STABLE with lag 0, DLT messages page renders, no page errors. Stack total ~6.3 GB. Note: its "Produce Message" button can write to the branch topics directly, bypassing the DC (local-only; read-only mode is an option) | ✅ |
| 2026-10-15 | **Incident: Docker stopped responding** while trying to open the WSO2 portal. The VM was near its ~7.7 GB limit after adding Kafka UI (host had 1.5 GB free); WSO2-A's JVM had crashed earlier (SIGSEGV in G1 GC, `hs_err_pid86.log`, Temurin 25.0.2), then the Docker engine stopped (named pipe gone). Cause of the JVM crash not proven: memory pressure is likely, a JDK bug not ruled out. My mistake: I added Kafka UI to the default stack without checking memory headroom | ⚠️ |
| 2026-10-15 | Fix, approved by the user: `%UserProfile%.wslconfig` `memory=9GB` (Docker now reports 8.7 GiB); Kafka UI back on demand (`docker compose --profile ui up -d kafka-ui`); Docker Desktop restarted. After restart: all 11 containers healthy, `setup.mjs` found everything ("exists"), **24/24** gateway checks, **36** isolation rules hold, sync B → 6 products read via WSO2-B, dashboard back. Memory now ~7.4 GB of 8.7 GB (WSO2 ~1.75 GB each and Oracle ~2 GB grow when there is room) | ✅ |
| 2026-10-16 | Part 2, Phase 0: machine checked for Kubernetes (§P2.2). Docker Desktop 4.95.0 has Kubernetes built in, **disabled**, set to kind mode (1 node, v1.36.1); containerd image store on; Windows 10 **Home** (no Hyper-V); WSL2 capped at 9 GB; **C: 16.1 GB free**. Current design can't scale listeners (1 partition, key = branch, table lock). Part 2 plan written | ✅ |
| 2026-10-15 | WSO2 portals verified in Edge for the first time: `https://localhost:9444/publisher` and `:9445/publisher` (login `admin` / `WSO2_ADMIN_PASSWORD`) list BranchAAPI / BranchBAPI; Developer Portals list them too | ✅ |
| 2026-10-16 | Part 2 planned (Kubernetes, immigration data, scaling, logging) and reviewed: decisions in §P2.10. Machine checked for local Kubernetes (§P2.2) | ✅ |
| 2026-10-16 | PRs #1–#4 merged into `main` in order with merge commits (stacked PRs: each retargeted to `main` first). The first attempt was blocked by Claude Code's permission check; the user approved a project-local allow rule (`.claude/settings.local.json`, git-ignored) for `gh pr merge` / `gh pr edit` | ✅ |
| 2026-10-16 | My mistake: `git checkout main` failed (stale local `main` vs uncommitted changes), but piping it through `tail` hid the error, so the next command fast-forwarded `phase-4-dashboard` instead. Only local branch pointers were wrong; reset to their `origin/` versions, nothing lost. Lesson: don't pipe a command whose failure must stop the chain | ✅ fixed |
| 2026-10-16 | Phase 5: Kubernetes enabled by the user (kind, 1 node, v1.36.1). First start ~26 min, almost all of it downloading images over Wi-Fi at ~0.5 MB/s; later starts reuse them. Survived a sleep/wake of the PC (12:33–14:06) without problems | ✅ |
| 2026-10-16 | Empty cluster memory: VM 1.18 GB used with only Kubernetes running; per process ~0.9–1.0 GB is Kubernetes (API server 260 MB, CoreDNS ×2 115 MB, controller manager 109 MB, kubelet 87 MB, …), the rest is Docker itself. `kubectl top node`: 258m CPU, 654 MiB working set | ✅ |
| 2026-10-16 | **Local images work without a registry:** a pod ran an image built seconds earlier with `docker build` (same digest, "pulled" in 0.5 s through Docker Desktop's built-in mirror). **Trap found:** rebuilding under the same tag is *not* picked up (`IfNotPresent` kept the old image; `Always` got the new one) → Phase 7 tags every build uniquely | ✅ |
| 2026-10-16 | **NetworkPolicy is enforced** (kindnet's built-in kube-network-policies engine). `scripts/check-k8s-cluster.sh`: **17/17 hold**: local image; open by default (+ internet control); deny-ingress blocks both clients; allow-from-a lets only a in; egress deny blocks the service, the internet and the host; egress allow re-opens only the service. Mutation check (allow the wrong namespace) → 3 FAIL, exit 3. A first mutant run stopped early because the previous run's namespaces were still terminating; the script now waits for them | ✅ |
| 2026-10-16 | **Isolation gap found (same kind as Phase 2's):** a pod without an egress policy reaches `host.docker.internal`, i.e. your PC's `kubectl port-forward` ports, and **port-forward traffic skips NetworkPolicy**, so it reached a pod whose ingress was closed to it. → every branch namespace gets default-deny egress (§P2.7) | ✅ |
| 2026-10-16 | `LoadBalancer` services are published by Docker Desktop on **0.0.0.0** (all interfaces, incl. Wi-Fi 192.168.181.222), plus 127.0.0.1 by `wslrelay`. Windows Firewall (Wi-Fi = Public profile, inbound blocked by default, no Docker allow rule) most likely stops other devices: not testable without a second device. → no LoadBalancer services; port-forward only (127.0.0.1) | ✅ |
| 2026-10-16 | metrics-server v0.9.0 (latest, 2026-07-13). Unchanged it fails: `x509: cannot validate certificate for 172.24.0.2 because it doesn't contain any IP SANs` (kubelet's self-signed cert). `k8s/addons/metrics-server` (Kustomize) adds `--kubelet-insecure-tls` (local only). `kubectl top node/pods` work | ✅ |
| 2026-10-16 | **Scheduler gap:** node allocatable = capacity = all 8.7 GiB; nothing is reserved for Docker or Kubernetes itself (already 490 Mi requested by system pods). Requests that overstate free memory would let the VM fill up before any pod shows Pending (the 2026-10-15 incident). → honest requests (= limits for Java pods) and a ResourceQuota per namespace (§P2.7) | ✅ |
| 2026-10-16 | **Decision on measured facts: keep Docker Desktop's Kubernetes (kind mode).** Everything Part 2 needs works; minikube would only add a second image store on a disk with 16 GB free | ✅ |
| 2026-10-16 | Phase 6 start: **JDK 21 had been uninstalled** (Windows Installer log, 10:27, before this work; the user confirmed it was an accident). Reinstalled Temurin 21.0.12.1 with winget, with the user's OK; same folder, so JAVA_HOME works again | ✅ |
| 2026-10-16 | Phase 6: PostgreSQL 18.6 (one per site), travel-pass schema + seed (40 fake passes: A 24, B 16, 3 revoked), `PassEvent` (UPSERT/DELETE, seq from a DC sequence, key = application number), DC sync / purge / **rush** (`POST /rush/{b}?count=`, up to 20000), checkpoint row locked per request so a later request also gets the higher seqs. Branch: one atomic `INSERT … ON CONFLICT DO UPDATE … WHERE event_seq < new` (no table lock), tombstones without personal data, counter check `POST /passes/search` (passport in the body, not the URL), `GET /summary?from=&to=` (progress through a request). 6 partitions per topic and DLT | ✅ |
| 2026-10-16 | Listener: Kafka 4 consumer protocol (KIP-848), `max-poll-records: 50`, `LISTENER_CONCURRENCY` (threads per pod), partition assign/give-up log lines, 20 s shutdown timeout, `RestClient.Builder` from Boot (passes `traceparent` on). **Retry policy changed:** with one event per pass, "8 retries then DLT" would dead-letter pass after pass while a branch is down. Now unavailable (5xx, no connection) is retried ~32 min (64 retries, ≤30 s apart) with a WARN per attempt; rejected / unreadable / other-branch go to the DLT at once | ✅ |
| 2026-10-16 | Logging: ECS JSON when `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, `site` and `service.node.name` (HOSTNAME = pod) fields, key-value fields (`applicationNo`, `seq`, `partition`, `offset`, `outcome`), INFO default. `TravelPass.toString()` hides personal data. **Bug: no trace id at first**: Boot 4's `spring-boot-micrometer-tracing-brave` only configures the tracer; `micrometer-tracing-bridge-brave` must be added. Fixed; `traceparent` now on every Kafka record | ✅ fixed |
| 2026-10-16 | **Personal-data leak found by the log test:** Spring logs every rejected request at WARN with the parser's message, which quotes the body (`Unrecognized token 'T12345678'` for a malformed counter lookup). Fix: `DefaultHandlerExceptionResolver` logger at ERROR in branch-service. Mutation check: without it, the passport number is in the log and the test fails | ✅ fixed |
| 2026-10-16 | Tests: `mvnw clean test` **39 run, 0 failures, 0 skipped** (DC 10, branch 19, listener 10). Incl.: 16 threads applying 400 shuffled, duplicated deliveries of 40 passes end in the newest state; tombstone vs late upsert; key = application number over >1 partition, one trace id per request; **a second listener copy joins: partitions split, every pass arrives; it leaves: the first takes all 6 back**; no personal data in DC / branch / listener logs (JSON + DEBUG). Dashboard 16 + proxy 7 pass | ✅ |
| 2026-10-16 | Mutation checks: seq guard removed → 5 branch tests fail; key = branch id → DC sync test fails; parser logger back to WARN → branch log test fails; listener logs a passport → listener log test fails; dashboard ignores the request range → race test fails; proxy without number limits → limits test fails. Restored; `mvnw clean`. **Trap found:** Vitest 5 reused a stale build of `app.ts` (module cache `node_modules/.vitest-cache`) while the spec file was unchanged, so the first dashboard mutation "passed". New `npm run test:fresh` clears it and `.angular/cache` | ✅ |
| 2026-10-16 | Own test bugs fixed on the way: the rush in the DC log test went to B (counted by other tests); `SpringApplicationBuilder.properties()` are defaults, so the second listener copy used application.yml's `localhost:9094` (now command-line args); "lost partitions" warned on a clean shutdown (Kafka calls revoke and lost; now only for partitions still owned); a dropped test (token cache) put back | ✅ fixed |
| 2026-10-16 | Compose: Oracle → `postgres-dc` / `postgres-a` / `postgres-b`, each on its own network with only its service (`dc-db`, `branch-a-db`, `branch-b-db`; branch ones internal). `check-isolation.sh`: **44/44** (new: listener, WSO2 and the other branch cannot reach a branch DB; nobody reaches the DC DB via the host). `setup.mjs` now updates an existing API's operations and deploys a new revision (Revision 2 on both; re-run: "operations up to date"). `check-gateways.mjs`: **32/32** | ✅ |
| 2026-10-16 | **Side effect found in my gateway check:** its POST /events probe (delete, seq 1) left a tombstone in both branch DBs and collided with the DC's real seq 1 (A showed `inRange` 25 of 24). Now the probe is an event for the other branch: 422 from the branch's own rule, nothing stored (verified: 0 rows). Tombstones removed | ✅ fixed |
| 2026-10-16 | Live: sync A 24 / B 16 through WSO2; **one trace id from the DC request through Kafka, the listener and WSO2 to the branch** (24 "Delivered" + 24 "APPLIED" lines): WSO2 passes `traceparent` on (risk resolved). Counter check through WSO2-A: A's traveller found, valid today; B's traveller unknown at A; no passport in the branch log | ✅ |
| 2026-10-16 | **Rush of 2000 FAILED at first:** "Expiring 35 record(s) … 10088 ms since batch creation": the producer's 10 s delivery timeout, while the listener, WSO2 and the branch were busy on 4 cores. DC counted 855 confirmed, the branch got 1954 (timed-out batches can still be written), 46 lost. Fix: `request.timeout.ms` 15 s, `delivery.timeout.ms` 60 s (idempotent producer, retries safe), `max.block.ms` stays 5 s. Rerun: 2000/2000 confirmed in 4 s, all applied after 36 s (~55 passes/s, 1 listener); CPU peaks: Kafka ~3 cores, WSO2-A ~3. Re-sync repaired the 46 (A 4024 = DC 4024). Cause "CPU starvation" is likely, not proven | ✅ fixed |
| 2026-10-16 | `demo-outage.sh`: branch A down during a rush of 20: WARN per attempt (WSO2: endpoint suspended), branch back → **20/20 delivered, 0 dead-lettered**. Postman (18 requests) via newman: **18/18 assertions** | ✅ |
| 2026-10-16 | Dashboard in headless Edge: sync A → "Receiving… Applied x of 4,144" → "In sync"; rush 300 on purged B → "Differs from DC: 316 vs 300"; sync B → "In sync"; 390 px: no horizontal scroll; dark mode readable. **Two problems:** (1) a stale answer showed "Applied 300 of 316" (a branch summary asked for the previous request's range arrived late): now only an answer for the current range counts; test added; (2) see next row | ✅ fixed |
| 2026-10-16 | **Branch B froze ~100 s** during that sync (HikariCP: "Thread starvation or clock leap detected, housekeeper delta=1m40s"): WSO2-B timed out, suspended the endpoint, the dashboard showed B unreachable (24 × 502). No restart, no OOM, no swap inside the VM, but **Windows had only 1.8 of 16 GB free** (9 GB VM + Edge + VS Code). Host paging of the VM or a long GC pause: not determined. Delivery resumed by itself, nothing lost | ⚠️ open |
| 2026-10-16 | Device limit, measured: the full compose stack is ~6.6 GB (each WSO2 ~2 GB); with it running, Testcontainers could not start Kafka ("Timed out waiting for … RUNNING"). Stop the stack before `mvnw test` (README) | ✅ |
| 2026-10-16 | Phase 7: Kubernetes switched on again by the user: the cluster was **recreated** (switching off removes it), so metrics-server was reinstalled. First check: **kubelet probes pass under default-deny ingress + egress** (node traffic is allowed), so the policies can stay strict | ✅ |
| 2026-10-16 | Manifests (Kustomize): `namespaces.yaml` (platform, dc, branch-a, branch-b + a memory ResourceQuota each), `platform/` (Kafka StatefulSet), `dc/` (postgres-dc, dc-service), `listener/{base,a,b}`, `branch/{base,a,b}` (postgres, branch, WSO2 with offset 1 / 2), 21 NetworkPolicies (default deny in and out per namespace, then one path each). Every Java pod: requests = limits, heap from the limit (`MaxRAMPercentage=55`, `ExitOnOutOfMemoryError`), startup / liveness / readiness probes, JSON logs | ✅ |
| 2026-10-16 | Design points found on the way: (1) **WSO2 needs its image's H2 databases**: Docker pre-filled a new named volume from the image, Kubernetes does not → an init container copies them once and creates the search-index folder (uid 10001). (2) **Certificate names**: WSO2's certificate is for `wso2-a`, and the listener checks hostnames → an ExternalName Service `wso2-a` in namespace dc (no new keys). (3) `enableServiceLinks: false`: otherwise Kubernetes injects `KAFKA_PORT=tcp://…`, which the Kafka image would read as a setting. (4) **Kustomize `includeSelectors`** also rewrote the NetworkPolicies' destination selectors ("Kafka pods with branch=a": none) → plain labels + an explicit Deployment selector | ✅ |
| 2026-10-16 | `k8s/deploy.mjs` (unique image tags `<commit>-dirty-<time>`, render + apply), `k8s/secrets.mjs` (Secrets from .env and generated/, piped as JSON), `k8s/port-forward.mjs` (same ports as before, 127.0.0.1, restarts dropped forwards). Bugs fixed: secrets before their namespaces; **the keystore Secret was refused**: client-side apply keeps a copy in an annotation (> 256 KB, and a second copy of every secret) → server-side apply; `mvnw.cmd` needs its full path from Node | ✅ fixed |
| 2026-10-16 | First deploy: the kubelet pulls **one image at a time**; WSO2 (748 MB) took ~3 min "including waiting" (the pull itself 213 ms, from the local store). The branches crashed until their databases had initialised (expected); the PostgreSQL readiness probe timed out at its 1 s default → 5 s. **Listeners crashed: "DNS resolution failed for kafka.platform.svc"**: a headless Service has no address until its pod is ready → a normal `kafka-bootstrap` Service for clients | ✅ fixed |
| 2026-10-16 | **WSO2 memory:** 1729 / 1723 Mi of a 1792 Mi limit; `memory.stat`: anon 1648 MiB (only 109 MiB file cache). `jcmd`: heap used 447 MB, committed 656, max 896 → ~1 GB outside the heap, so a full heap would have exceeded the limit. → limit 2 GiB, `-Xmx768m`; branch quotas 4.5 GiB | ✅ fixed |
| 2026-10-16 | `setup.mjs` against the two **new** WSO2s in the cluster (via port-forward): roles, users, APIs (4 operations), apps and keys created; listener credentials → Secrets → listeners restarted. Sync A (24) / B (16): all delivered and applied | ✅ |
| 2026-10-16 | Checks on Kubernetes: `check-isolation.sh` rewritten for `kubectl exec`: **46/46 hold** (new: no route to your PC or the internet; pod IPs blocked, not just names; an unlabelled pod in branch-a reaches nothing, with a DNS control). `check-gateways.mjs` (forged JWTs now from inside the WSO2 pod, the only pod allowed to reach the branch): **32/32**. `demo-outage.sh` (branch scaled to 0): **20/20 delivered, 0 dead-lettered**. Newman **18/18**. Kafka UI as `k8s/optional/kafka-ui`: cluster ONLINE, groups listener-a/b STABLE, lag 0 | ✅ |
| 2026-10-16 | **Healthy pods restarted by their liveness probes** during the first dashboard walkthrough (branch B exit 137, listener A exit 143: "failed liveness probe", "context deadline exceeded" at the 1 s default). At the same time metrics-server's and the scheduler's probes timed out: the whole node was slow. **Windows had 1.1 GB free** (Docker VM 7.5 GB + browsers + VS Code). → liveness 5 s timeout, restart only after ~60 s; readiness 3 s. Lesson: a strict liveness probe restarts slow pods and adds load | ✅ fixed |
| 2026-10-16 | **Port-forwards went stale** after the probe redeploy replaced their pods: kubectl did not exit, it only logged "error creating error stream … Timeout occurred", so requests hung (the second walkthrough waited forever). `port-forward.mjs` now also restarts a forward when kubectl reports such an error or its local port stops answering. Verified: DC pod replaced by a rolling restart → "lost connection to pod" → forward restarted → `localhost:8090` answered 2 s after the rollout | ✅ fixed |
| 2026-10-18 | **Dashboard said "Sync A failed: HTTP 502 … ETIMEDOUT", yet all 144 events arrived.** The proxy (`server.mjs`) gave every call 5 s; the DC answers a sync / purge / rush only once Kafka has stored every event (up to 75 s), and on a busy node that took longer. → commands 90 s, reads still 5 s; new proxy test (slow fake DC), mutation-checked (fails without the longer timeout) | ✅ fixed |
| 2026-10-18 | **Dashboard walkthrough on Kubernetes** (headless Edge; after a PC reboot, the cluster came back by itself with its data, every pod restarted once): purge B (616 deletes) → "Purged"; sync A (144) → "In sync"; rush B 300 → "Differs from DC" (DC 916, B 300); sync B (916) → "In sync". 390 px + dark mode: no horizontal overflow; 0 console errors; **no pod restarts** during the run. One listener delivered the 916 events in 26 s (~35/s: the Phase 8 baseline). The ~30 s before each final badge was the **script**: Playwright's `textContent()` waits 30 s for the progress line, which disappears when done; without that wait, sync A was "In sync" after 5.7 s | ✅ |
| 2026-10-16 | Memory on Kubernetes (all pods, `kubectl top`): node 6.8–7.1 GiB of 8.9 (76–79%). WSO2 ~1.7 GiB each, Kafka 346 Mi, DC 308 Mi, branches ~270 Mi, listeners ~200 Mi, databases ~37 Mi, Kubernetes itself ~0.6 GiB. Room for scaling in Phase 8: ~1.5 GiB, ~4 GiB with branch B parked | ✅ |
| 2026-10-16 | **Docker Compose removed** (P2-5): `docker-compose.yml` deleted; scripts, README, CLAUDE.md (a "current state" section) and comments updated. The stopped `bdc-*` containers and volumes (incl. Oracle's 5.5 GB) are still on the PC: the user's decision (README) | ✅ |
| 2026-10-18 | Phase 8a: HPA for the branch service (1–3 copies, 70% of the 200m CPU request; up at once ≤ 2 per 30 s, down after 2 min, 1 a minute); the Deployment no longer sets `replicas` (a redeploy would reset the HPA's choice). k6 2.3.0 load test as a Job **inside** branch A through WSO2-A, with its own read-only WSO2 application `counter-a` (asked for the sync scope: granted only read; `POST /events` → 403) and two NetworkPolicies that are removed after each run. TLS: k6 trusts exactly WSO2-A's certificate (`SSL_CERT_FILE`, Go). `k8s/load-test.mjs` prints copies / HPA CPU / CPU per pod every 15 s | ✅ |
| 2026-10-18 | Memory: with everything running, pods requested **92%** of the node (room for 1 more branch copy) → branch B parked for the lab (57%); unparked after 174 s, its 916 passes intact | ✅ |
| 2026-10-18 | **HPA under load** (counter lookups/s; p95 / p99): 40/s with a **cold** pod: scaled 1 → 3, 12 / 164 ms, 53 dropped; the same with the HPA pinned to 1: 58–95m CPU, 5.5 / 7 ms, 0 dropped. 150/s warm: scaled 1 → 3 (3 copies 400–560m together), 9.7 / 32 ms; pinned to 1: ~205m, 5.8 / 34 ms. **One warm copy was enough at both rates**: no CPU limit, and the HPA's target is relative to the request. A cold pod's JIT work looked like load; each new copy used **1.8–2.1 cores for ~30 s** to start; load spread by connection (one copy busy, the others idle); WSO2 3–4× the branch's CPU. Stated in the guide: more copies on one node show the mechanism, not capacity | ✅ measured |
| 2026-10-18 | **Scale by hand:** listener A 1 → 3: every copy owned 2 of 6 partitions ~40 s after the command, handed over gradually (KIP-848); a sync of 144 split 45 / 48 / 51 by partition. Branch A 1 → 3 with the HPA in charge: back to 2 at 141 s, 1 at 203 s. Gotcha: `kubectl logs -l` shows only 10 lines per pod without `--tail=-1` (a count of 144 came out as 30) | ✅ |
| 2026-10-18 | After the lab: `setup.mjs` rerun changes nothing; isolation **46/46**, gateways **32/32**; no pod restarts during the lab. `docs/k8s-scaling-guide.md`: exercises 1–2 with these numbers | ✅ |
| 2026-10-18 | **Listener copies in a big rush** (branch B parked): rush of 10,000 passes at A, listener scaled 1 → 3 after 65 s. One listener delivered **120–130 events/s**; branch A's HPA went 1 → 3 **by itself** (applying events costs CPU); while the new listener copies started the rate dipped to ~50/s, then 110–134/s: **no faster with 3** (the shared 4 cores and WSO2 are the limit, not the listener). 3,000 passes were too few to show anything (done before the new copies were ready) | ✅ measured |
| 2026-10-18 | **Scaling demo** for the user (guide, "The demo (k9s)"): k9s 0.51.0 installed with winget; its keys checked in its docs and source (`s` = Scale on a deployment but Shell on a pod; Ctrl+K kills a pod; `--readonly` would also disable Scale). Rancher not used: its smallest test setup needs ≥ 4 GB RAM. Docker Desktop 4.95's Kubernetes view (pods/deployments per namespace, live) as the fallback | ✅ |
| 2026-10-19 | Phase 8b: **KEDA 2.21.0** (tested by KEDA with Kubernetes 1.34–1.36; ours 1.36.1) as `k8s/addons/keda`, installed by `deploy.mjs` (server-side: its CRDs are too big for client-side apply; its webhooks fail open). A **ScaledObject per listener**: Kafka lag, 500 events per copy, 1–3 copies (6 = partitions would be the limit; memory), counts that split the 6 partitions evenly; down after 2 min, 1 a minute. Kafka NetworkPolicy: KEDA's operator may reach Kafka. The listener Deployment no longer sets `replicas`. **Kafka 4's new consumer protocol (KIP-848) works with KEDA's Kafka scaler** (risk from §P2.9: lag read correctly, 0 = idle). Idle, the HPA shows 500/500: KEDA reports copies × 500 for the count it wants | ✅ |
| 2026-10-19 | **Parking needs KEDA's pause**: KEDA enforces its minimum, so `kubectl scale … --replicas=0` is undone (it started a parked listener-b within seconds). `node k8s/park.mjs b` (and `--unpark`) pauses the listener with `autoscaling.keda.sh/paused-replicas: "0"`, scales the branch, WSO2 and database; `deploy.mjs` unparks every branch (without waiting: WSO2-B once needed over 6 minutes). Mistake: my first apply removed the listener's `replicas` and so reset the user's hand-scaled listener-a (3 copies from their k9s practice) to 1 | ✅ fixed |
| 2026-10-19 | **KEDA measured:** 8 s from a 10,000-pass rush to "New size: 3"; new copies ready ~50 s later on a busy node; 1 listener (KEDA paused at 1) 10,000 in 116 s (~90/s); 3 warm copies drained a 28,445-event backlog at ~200/s; back to 1 after the 2-minute window. Branch A's HPA scaled too (applying events costs CPU). Two of my runs were invalid and are not used: a stopped Windows shell kept its child processes running, so two rushes overlapped, and a later run went on after an API error and sent a rush that the overloaded DC published only 3,000 of 10,000 of (FAILED). Lesson: on Windows, kill test processes by command line, not only the task | ⚠️ measured, 2 runs discarded |
| 2026-10-19 | **The DC ran out of memory** (`OOMKilled`, 448 MiB): its sync and purge load every pass of a checkpoint at once, and the rushes had grown A to 43,144, later 83,144 passes (a Sync A from the dashboard). The heap was at ~200 of ~246 MB: the rest of the JVM (metaspace, JIT code, threads) took the remainder. Once also during a rush on a busy node. A killed DC leaves its request at PENDING / "published 0" although most events had gone out (the known no-outbox gap). → next PR: sync/purge in chunks, memory headroom, a reset of the demo data | ⚠️ open |
| 2026-10-19 | **This PC's limit, measured three times today:** with Windows at 0.6–0.7 GB free (Chrome 3–4 GB, VS Code 1.5–1.8 GB, the VM 6–6.7 GB) the Kubernetes API answered too slowly: KEDA's operator lost its leader lease and restarted (6×), metrics-server restarted 12×, every port-forward dropped, the dashboard timed out (WSO2-A 4–5 s per answer), WSO2-B failed its 6-minute startup window twice. Also: a second `port-forward.mjs` (mine plus the user's) fights for the same ports. Guide: check ≥ ~2 GB free before a demo | ⚠️ |
| 2026-10-19 | Checks for 8b with **branch B parked** (no memory for it): isolation 48 ok / 4 FAIL; the 4 are B's "reachable" rules and B's 6 "blocked" rules prove nothing without its pods, so **42 of 52 rules really verified, all hold**, including the 6 new KEDA rules (only KEDA's operator reaches Kafka; it reaches nothing else of ours). `check-gateways.mjs` **not run** (needs both WSO2s; this PR does not touch them). Full run with B: still to do | ⚠️ partly |
| 2026-10-19 | **DC fix, part 1: chunks.** A request now reads only the application numbers (projection query) and takes a sequence number per pass; after commit the publisher loads, sends and awaits **1,000 at a time** (`dc.publish.chunk-size`) and records the confirmed count per chunk (a request cut off halfway now shows how many Kafka had, not 0). The rush saves 500 at a time and clears the persistence context. Tests: chunks of 7 in `DcIntegrationTest`, so every sync, purge and rush crosses chunk boundaries; rush test 1,201 passes; 2 new publisher tests; **12/12, Skipped: 0**; 2 mutations caught (no progress recorded; carrying on after a failed chunk) | ✅ |
| 2026-10-19 | **DC fix, part 2: the JVM's memory.** Chunks alone were not enough: after a sync of 93,144 passes the DC held 433–443 MiB of its 448 (OOMKilled once more). Native Memory Tracking (printed at exit; the JRE has no jcmd): heap 180 (grown to its maximum), metaspace + class data + symbols 160, compiled code 33, plus a 48 MB JIT-compiler peak that the C library kept. → DC only: `JDK_JAVA_OPTIONS=-XX:MaxRAMPercentage=30 -XX:TrimNativeHeapInterval=30000` (heap 136 MiB; freed native memory handed back every 30 s), `MALLOC_ARENA_MAX=2`. **Measured: syncs of 93,144 passes in 13 s and 9 s (was 187 s with GC stalls up to 17.6 s), ~385 MiB after (peak 393), 371 MiB a minute later; a rush of 10,000 in 2.5 s** | ✅ fixed |
| 2026-10-19 | `k8s/reset-data.mjs`: demo data back to the 40 seeded passes in ~10 s: per running branch the listener is held at 0, its group moved to the end of the topic (475,720 waiting events skipped), its database emptied; then the DC's rush passes deleted (104,020), listeners handed back to KEDA, branches synced. A parked branch is skipped. Bug found running it: `isParked` counted a held listener as parked (A skipped) → parked = branch service at 0. `deploy.mjs --keep-parked` for low memory. Not done: the dashboard shows 0 progress while a request is still PENDING (publishing); with chunks a big sync is PENDING for ~10 s. Risk noted, not changed: the branch service (301 of 448 MiB now) and the listener (205 of 384) keep the image's 55% heap; never OOMKilled so far | ✅ / ⚠️ |

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

### Phase 5: Hardening and docs (superseded by [Part 2](#part-2-kubernetes-immigration-data-scaling-and-logging))
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

---

# Part 2: Kubernetes, immigration data, scaling and logging

Requested 2026-10-16. Status: **plan reviewed 2026-10-16** (decisions in §P2.10). Phases 5–6 done 2026-10-16, Phase 7, 8a and the demo on 2026-10-18, 8b and the DC sync fix on 2026-10-19. Next: Phase 9.

## P2.1 Goals

- Run the POC on **local Kubernetes** instead of Docker Compose.
- Change the dataset to **immigration**: HQ (the DC) sends approved travel passes to the entry checkpoints (the branches).
- Make the **branch service** and the **listener** scale out for a holiday rush, and learn how: by hand, automatically under load, and on a schedule for known holidays.
- Our side is the code and how it's deployed. In real life Kafka belongs to another team, so we keep using the same single Kafka (`apache/kafka:4.2.2`), moved into the cluster, and don't tune it beyond what we need (topics and partitions).
- Good logging, both in the code and in the deployment.

## P2.2 What I checked (2026-10-16)

| Item | Found |
|---|---|
| Host | Windows 10 **Home** 19045, i7-7700HQ (**4 cores / 8 threads**, 2017), 16 GB RAM |
| Docker | Docker Desktop 4.95.0, engine 29.8.2; WSL2 VM capped at **9 GB** by `.wslconfig` (8.7 GiB usable); **containerd image store on** |
| Kubernetes | Built into Docker Desktop, **disabled**. Configured for **kind** mode, 1 node, v1.36.1. `kubectl` v1.36.1 comes with Docker Desktop. No minikube, kind, helm |
| Disk | **C: 16.1 GB free.** Docker's disk file is 27.6 GB: Oracle image 6.5 GB, this project's Oracle volume 5.5 GB, it-access-portal's Oracle volume 3.2 GB |
| Containers | None running |

### Docker Desktop Kubernetes or minikube?

**Recommendation: Docker Desktop's built-in Kubernetes, kind mode, 1 node.** On this PC minikube would not be lighter:

- Windows 10 Home has no Hyper-V, so minikube would use its `docker` driver. Its node then runs as a container **inside the same Docker Desktop VM**, sharing the same 9 GB. Docker Desktop's kind mode works the same way.
- minikube keeps its own image store inside its node, so every image we build is stored twice (`minikube image load`). Disk is the tightest resource here.
- The VirtualBox driver would add a second VM with its own fixed RAM next to WSL2, which is worse.
- What minikube does better: one-line add-ons (`metrics-server`, `--cni=calico` for NetworkPolicy). On Docker Desktop we install metrics-server and KEDA ourselves with `kubectl apply`, which is fine and teaches more.

The heavy parts of this stack are not Kubernetes (~1 GB, measured in Phase 5) but the two WSO2 servers (~3.2 GB) and Oracle (~2 GB).

The Docker docs don't answer these, so Phase 5 tested them (details in the progress log, re-runnable with `bash scripts/check-k8s-cluster.sh`):

| Question | Answer (2026-10-16) |
|---|---|
| Does kind mode enforce NetworkPolicy? | **Yes**, ingress and egress (kindnet's built-in kube-network-policies engine). 17/17 checks, mutation-checked |
| Can it run images built with `docker build`, without a registry? | **Yes**, through Docker Desktop's built-in mirror. But a rebuild under the same tag isn't picked up: tag every build uniquely |
| How are `LoadBalancer` services published? | On **0.0.0.0**, i.e. the Wi-Fi too (only the firewall stands in the way). Not used: `kubectl port-forward` (127.0.0.1) instead |
| How much memory does the empty cluster use? | ~0.9–1.0 GB for Kubernetes; 1.18 GB VM total including Docker. Idle CPU ~0.26 core |
| (found on the way) What does the scheduler think it has? | All 8.7 GiB: nothing reserved for Docker or Kubernetes, so requests must be honest and capped by quota |
| (found on the way) Can a pod reach your PC? | **Yes**, via `host.docker.internal`, including port-forwards, which skip NetworkPolicy. Only an egress policy stops it |

## P2.3 Device limitations

| Limit | Effect | What we do |
|---|---|---|
| 9 GB for all containers (host 16 GB) | Room for about 4–6 extra Java pods beyond the base stack, and only without Oracle | PostgreSQL instead of Oracle (P2-1); a memory limit on every pod; during heavy labs, park branch B (`kubectl scale --replicas=0`); central logs and Kafka UI only on demand |
| 4 cores / 8 threads shared by the cluster, WSO2, the load generator and the pods under load | More branch-service pods on the same CPU **don't add real capacity**. CPU autoscaling here shows how scaling works, not a faster system | Say so in the results. Listener scaling **does** speed up: a listener mostly waits for HTTP replies through WSO2, and more consumers wait in parallel (until WSO2 or the database becomes the limit) |
| A Java pod needs ~20–40 s to start with a CPU limit | Autoscaling reacts late to a sudden rush | Scale before known holidays (scheduled scaling); a startup probe so no traffic reaches a pod before it's ready |
| C: 16 GB free | Kubernetes images add ~1.5–2.5 GB; Docker's disk file never shrinks by itself | Small images (Postgres); after the switch you may delete `branch-dc_oracle-data` (5.5 GB): your decision; `docker builder prune` |
| One node | No node failures, spreading over nodes or HA to try | Stated as a simplification; kind mode can do 2 nodes later if memory allows |
| Incident on 2026-10-15: Docker stopped when the VM was near its memory limit | | A memory limit on every container: Kubernetes then kills one pod instead of the whole VM struggling |
| The node offers all 8.7 GiB to pods; nothing is reserved for Docker (~0.4 GB) or Kubernetes (~1 GB) | Scheduling alone won't stop the VM from filling up: pods only go Pending when their *requests* don't fit | Memory request = limit for Java pods (what they really use); a ResourceQuota per namespace so the autoscalers can't request more than the budget below |

### Memory budget (estimates; Kubernetes measured in Phase 5, the rest in Phase 7)

| Item | Estimate |
|---|---|
| Kubernetes itself (node, control plane, CoreDNS, load-balancer helper) | ~1.0 GB (measured: 0.9–1.0 GB) |
| metrics-server + KEDA | ~0.3 GB |
| Kafka | ~0.5 GB |
| PostgreSQL ×3 (DC, A, B) | ~0.2 GB |
| WSO2 ×2 | ~3.2 GB |
| dc-service, branch-a, branch-b, listener-a, listener-b (1 pod each) | ~1.4 GB |
| **Base** | **~6.6 GB of 8.7 GB** |
| Left for scaling | ~2 GB: about 4–6 more Java pods |
| On demand: Loki + Alloy + Grafana; Kafka UI | ~0.5 GB; ~0.4 GB |

With Oracle instead of Postgres the base is ~8.4 GB, which leaves no room to scale.

## P2.4 How scaling works for our two services

**Branch service (HTTP, behind WSO2).** It's stateless: the data is in the branch's database, so any pod can answer any request. A Deployment runs N copies behind one Service, and the Service spreads **connections** over the pods that are ready. A HorizontalPodAutoscaler (HPA) changes N from the average CPU use. The limits are the database (every pod opens its own connection pool) and WSO2 (one instance, not ours to scale).

**Listener (Kafka consumer).** All copies join the same consumer group (`listener-a`), and Kafka shares the topic's **partitions** between them. So **the number of partitions is the maximum number of useful listener copies**. The right signal is lag (records waiting), not CPU: KEDA reads the group's lag from Kafka and sets the number of copies.

**Holidays are known in advance.** So: scale up **before** the rush (a KEDA cron trigger, e.g. school holidays), plus react to load (CPU or lag) for surprises.

### Why the current code can't scale the listener (must change)

Today each branch topic has **1 partition**, every record's key is the branch id, and each event is a full snapshot of the branch. A second listener pod would get no partition and sit idle. The branch also locks a whole table (`LOCK TABLE applied_event`), so deliveries are applied one at a time. To scale:

- **One event per travel pass**, key = application number. Records spread over **6 partitions** per branch topic (so up to 6 listener pods per branch), and Kafka still keeps the order **per pass**.
- **A sequence number on every event**, taken from the DC. The branch applies an event only if it's newer than what it has for that pass (`INSERT … ON CONFLICT … DO UPDATE … WHERE`). That needs no table lock and is safe with many listener pods and many branch pods at once. A delete leaves a tombstone, so a late retry can't bring a pass back.
- This **replaces decision 5** (full-replace snapshot). Sync now re-sends every pass of the branch; purge sends a delete for every pass.

## P2.5 Immigration dataset (proposal)

HQ (the DC) approves travel passes (visas, entry permits). Each pass names the **entry checkpoint** the traveller will use, and that checkpoint (the branch) gets the pass so officers can check it at the counter. Checkpoint **A = airport**, **B = land border**. Branch A must never see B's travellers. All names and passport numbers are fake (generated).

| Table (DC) | Columns |
|---|---|
| `checkpoint` | code (A, B), name, kind (AIRPORT, LAND) |
| `travel_pass` | application_no (PK, e.g. `APP-2026-000123`), passport_no, full_name, nationality (ISO 3166 alpha-3), date_of_birth, pass_type (TOURIST, BUSINESS, STUDENT, TRANSIT), checkpoint_code, valid_from, valid_until, status (APPROVED, REVOKED), updated_at |
| `sync_log` | as now, plus kind (SYNC, PURGE, RUSH) and number of records |

The branch keeps a copy of its passes, plus `event_seq`, `deleted` (tombstone) and `received_at`.

Event on `dc.branch-a`, key = application number:

```json
{ "eventId": "uuid", "seq": 1042, "type": "UPSERT", "branchId": "A", "occurredAt": "2026-12-19T08:00:00Z",
  "applicationNo": "APP-2026-000123",
  "pass": { "passportNo": "X1234567", "fullName": "…", "nationality": "SGP", "dateOfBirth": "1990-05-01",
            "passType": "TOURIST", "validFrom": "2026-12-20", "validUntil": "2027-01-03" } }
{ "eventId": "uuid", "seq": 1043, "type": "DELETE", "branchId": "A", "occurredAt": "…", "applicationNo": "APP-2026-000123" }
```

| Site | Endpoint | Change |
|---|---|---|
| DC | `GET /data`, `GET /summary` | paged (`?branch=&page=&size=`); counts per checkpoint. After a rush there are thousands of rows |
| DC | `POST /sync/{branch}`, `POST /purge/{branch}` | one event per pass |
| DC | `POST /rush/{branch}?count=5000` | **new**: the holiday rush. Creates N approved passes and publishes them |
| Branch | `GET /data`, `GET /summary` | paged; counts |
| Branch | `POST /passes/search` `{"passportNo": …}` | **new**: the counter lookup, the busy path for the load test (read scope). A POST, so the passport number never appears in a URL (gateway and proxy access logs) |
| Branch | `POST /events` | one event (UPSERT or DELETE) |

## P2.6 Logging

**In the code**

- JSON logs on stdout in Kubernetes (Spring Boot's built-in structured logging, ECS format); readable text when run locally.
- Every line carries the service, branch, **pod name** (passed in by Kubernetes) and **trace id**. One trace id follows a pass from the DC request through the Kafka header, the listener and WSO2 to the branch (Micrometer Tracing, W3C `traceparent`). To check: WSO2 passes the header on; fallback: our own correlation-id header.
- Business events as fields you can filter on: `applicationNo`, `seq`, `partition`, `offset`, `outcome` (APPLIED / DUPLICATE / STALE).
- **No personal data in logs**: never passport numbers, names or birth dates; the application number identifies a pass. A test captures the log output and fails if a passport number or name appears (with a mutation check).
- Levels: INFO = one line per business outcome; WARN = a retry; ERROR = gave up / dead-lettered. No INFO line per counter lookup (thousands under load; metrics count those). Today's `com.branchdc: DEBUG` becomes INFO, changeable per environment through a ConfigMap.
- The listener logs which partitions it gets and loses, so after scaling the logs show which pod reads what.

**In the deployment**

- Standard labels on everything (`app.kubernetes.io/name`, `app.kubernetes.io/part-of`, `branch`), e.g. `kubectl logs -n dc -l app.kubernetes.io/name=branch-listener --prefix -f`.
- Kubernetes keeps a pod's logs only while the pod exists (rotated at 10 MiB). **When the autoscaler removes a pod, its logs go with it.** That's why autoscaled services need central logs.
- Central logs, on demand: Grafana Loki (single process, files on disk, short retention), Grafana Alloy (collects every pod's logs **and** the Kubernetes events, so scaling decisions show up next to the app logs) and Grafana. ~0.5 GB.
- Kubernetes events: autoscaler decisions, out-of-memory kills, failed probes.

## P2.7 Kubernetes layout

| Namespace (= environment) | Contents | Notes |
|---|---|---|
| `platform` | Kafka (StatefulSet), Kafka UI on demand | "The Kafka team's": we only use topics |
| `dc` | dc-service, postgres-dc, listener-a, listener-b | The listeners stay in the DC environment |
| `branch-a` | branch-a, postgres-a, wso2-a | Same base for both branches, different overlay |
| `branch-b` | the same for B | |
| `keda`, `kube-system` | KEDA, metrics-server | |
| `observability` | Loki, Alloy, Grafana (on demand) | |

- **Kustomize** (built into `kubectl`; nothing to install): `base/` plus `overlays/a` and `overlays/b`, i.e. "same service, different location". Helm isn't needed.
- **NetworkPolicies replace the internal Docker networks.** Every namespace denies everything by default, **ingress and egress**, then allows only: listener-a → Kafka and → wso2-a; wso2-a → branch-a; branch-a → postgres-a; DNS. Default-deny egress also matters for your PC: without it a branch pod reaches `host.docker.internal`, including the port-forwards to the DC (Phase 5 finding). The isolation and gateway check scripts move to `kubectl exec` and must pass again (36 + 24 checks), plus "branch → host.docker.internal blocked".
- **Images:** built with `docker build`, no registry; every build gets a unique tag (git commit + a dirty marker), because a rebuild under the same tag is silently ignored (Phase 5 finding).
- **Memory:** requests = limits for Java pods; a ResourceQuota per namespace, because the node reserves nothing for Docker or Kubernetes (Phase 5 finding).
- **WSO2 in Kubernetes:** `deployment.toml` as a ConfigMap; keystores and passwords as Secrets; its state on a PersistentVolume (`fsGroup` replaces the chown container). Its certificate also gets the in-cluster names (`wso2-a.branch-a.svc`). Not scaled (platform team); in a real holiday the gateways would scale too.
- **Secrets** are created from `.env` and `infra/wso2/generated/` by a script (`kubectl create secret … --dry-run=client -o yaml | kubectl apply -f -`). Nothing secret goes into git.
- **From your PC:** `kubectl port-forward` to the same ports as today (8090, 9444/8244, 9445/8245), so the dashboard, Postman and the scripts barely change. It listens on 127.0.0.1 only. No `LoadBalancer` services: Docker Desktop publishes them on 0.0.0.0, the Wi-Fi included (Phase 5 finding). No ingress controller (memory).
- **Every Java pod:** CPU and memory requests/limits; startup, readiness and liveness probes (`/actuator/health/...`); graceful shutdown (the listener finishes the current record, commits and leaves the group); JVM heap sized from the container limit (`-XX:MaxRAMPercentage`) instead of `-Xmx256m`; `-XX:+ExitOnOutOfMemoryError`. Flyway still runs at startup; with several pods starting together it takes a database lock, so only one migrates.
- Docker Compose is removed once the same checks pass on Kubernetes (P2-5).

## P2.8 Phases

Same way of working: one branch + PR per phase; exit criteria checked by running them.

### Phase 5: Kubernetes check (no app code) ✅ done 2026-10-16

- You enable Kubernetes in Docker Desktop (Settings → Kubernetes → kind, 1 node). About 1 GB to download.
- I measure the empty cluster's memory, run an image built with `docker build` without a registry, test **NetworkPolicy enforcement** (default deny → the connection must fail; allow → it works), check how LoadBalancer services are published, and install metrics-server (`kubectl top`).
- **Exit:** results recorded here; Docker Desktop vs minikube decided on measured facts.
- **Result:** all answered (§P2.2 table, progress log). Docker Desktop's Kubernetes stays. Delivered: `scripts/check-k8s-cluster.sh` (17 checks) and `k8s/addons/metrics-server`. Three findings changed §P2.7: default-deny egress (host access), unique image tags, honest memory requests + quotas.

### Phase 6: Immigration data, scalable code, logging in the code ✅ done 2026-10-16

- PostgreSQL, new Flyway migrations and seed (~40 passes), new event contract, DC sync / purge / rush, branch applies by sequence (no table lock), counter lookup, paged reads.
- Listener: 6 partitions; Kafka 4's new consumer group protocol (KIP-848: smoother rebalances when pods come and go); graceful shutdown; partition-assignment logs.
- Logging as in P2.6 (code side).
- Dashboard (counts per site, latest passes, a Rush button) and Postman collection updated.
- **Result:** as planned, plus (progress log): listener retries for an unavailable branch now ~32 min instead of 2 (events wait in Kafka); producer timeouts sized for a rush on a busy PC; a request-body logging leak closed; a side-effect-free gateway probe; dashboard progress per request. Open: the 100 s branch freeze.
- **Exit:** `mvnw clean test`: 0 failures, **Skipped: 0** (Testcontainers Postgres + Kafka). Tests: slice per branch; key = application number; duplicate and out-of-order events end in the same state (many threads at once, like many pods); tombstone vs late upsert; two listener instances on 6 partitions share the work and every pass arrives once applied; no personal data in logs. Mutation checks, then `mvnw clean`.

### Phase 7: Run it on Kubernetes ✅ done 2026-10-18

- Kustomize manifests, namespaces, Kafka, Postgres ×3, the apps, WSO2 ×2, NetworkPolicies, the secrets script, a port-forward script; `setup.mjs` run against the WSO2s in the cluster.
- **Exit:** the isolation and gateway checks pass on Kubernetes; live sync / purge / rush; dashboard works; memory measured. Compose removed.
- **Result:** all met (progress log). Changed on the way: WSO2 init container for its H2 databases, ExternalName alias for the certificate names, a `kafka-bootstrap` Service, WSO2 2 GiB / heap 768 MB, generous liveness probes, server-side apply for secrets, self-healing port-forwards, a longer dashboard timeout for DC commands.

### Phase 8: Scaling lab

Split in two PRs (one phase in one PR was too long to follow, Phase 6):

- **8a: the branch service on CPU** ✅ done 2026-10-18. HPA for the branch service; the k6 Job inside branch A through WSO2-A, with its own read-only WSO2 application (`counter-a`); `k8s/load-test.mjs`; guide exercises 1 (by hand) and 2 (HPA under load).
- **Demo** (added 2026-10-18 at the user's request: "I just want to demo the scaling"): a k9s walkthrough of both stories in the guide.
- **8b: the listener on Kafka lag** ✅ done 2026-10-19 (asked for by the user: "yes continue 8b"). KEDA 2.21.0, a ScaledObject per listener, `k8s/park.mjs`, exercise 3. **The holiday schedule (exercise 4) is dropped** (user, 2026-10-18); PodDisruptionBudgets and exercises 5–7 not done.

The original list:

- metrics-server, KEDA (current release, checked then), PodDisruptionBudgets.
- Branch service: HPA on CPU, then the same through KEDA with a holiday cron trigger. Listener: KEDA on Kafka lag (at most 6 = partitions) plus the holiday cron trigger.
- Load: `POST /rush/A?count=…` for events; a k6 Job **inside** the cluster for counter lookups through WSO2-A (`port-forward` always goes to one pod, so it can't be used to load several).
- `docs/k8s-scaling-guide.md`: exercises you run yourself, with what to watch and what I measured: (1) scale by hand; (2) HPA under load; (3) lag → more listeners → time to drain; (4) the holiday schedule; (5) scale down: rebalance, no lost or doubled passes; (6) kill a pod in the middle of a rush; (7) threads vs pods for the listener: the same rush drained by 1 pod × 6 threads, 3 pods × 2 and 6 pods × 1, comparing drain time, memory and what a killed pod does to each. In Spring Kafka every thread is a full consumer (its own connections and group membership), so threads and pods count the same toward the partition limit, and KEDA's `maxReplicaCount` must be partitions ÷ threads per pod.
- **Exit:** every exercise run on this PC with the numbers recorded, including what didn't speed up and why.

### Phase 9: Logging in the deployment

- Loki + Alloy + Grafana on demand. Saved queries: one pass end to end by trace id, errors, scaling events, logs of pods that no longer exist.
- `docs/logging.md`: the rules for the code and the deployment.
- **Exit:** in Grafana, follow one rush pass from the DC to branch A by its trace id, including on a pod that was removed by a scale-down.

## P2.9 Risks to check early

| Risk | Plan |
|---|---|
| Kind mode doesn't enforce NetworkPolicy | ✅ Resolved in Phase 5: it does (ingress and egress) |
| The kind node can't see locally built images | ✅ Resolved in Phase 5: it can; unique tags needed |
| WSO2 behind port-forward: portal redirects, token URL, certificate names | Same ports inside and outside (offsets 1 and 2, as today); in-cluster names added to the certificates |
| KEDA's Kafka scaler with the new consumer group protocol | Check in Phase 8. Fallback: classic protocol with the cooperative-sticky assignor |
| WSO2 keeps its connections to the branch open, so new branch pods get little traffic at first (a Service balances connections, not requests) | Watch it in the lab (logs show the pod name). Tomcat closes a connection after 100 requests by default, which should rebalance |
| WSO2 drops `traceparent` | ✅ Resolved in Phase 6: it passes it on (one trace id from the DC to the branch) |

## P2.10 Decisions (answered by the user, 2026-10-16)

| # | Question | Decision |
|---|---|---|
| P2-1 | Database | **PostgreSQL 18, one per environment** (DC, A, B). The user suggested SQLite if the data stays small; not used, because SQLite is a file inside one pod: 3 branch pods would each have their own file and their own data. Pods can only share data through a database server they all connect to. Oracle leaves no memory to scale |
| P2-2 | Event model | **One event per pass**, key = application number, 6 partitions per branch topic, sequence numbers. Replaces decision 5 (full-replace snapshot) |
| P2-3 | Dataset | **Travel passes per entry checkpoint** (A airport, B land border), fake travellers, a Rush endpoint for the holiday load |
| P2-4 | Central logs | **Loki + Alloy (DaemonSet) + Grafana, on demand** |
| P2-5 | Docker Compose | **Remove** once Kubernetes passes the same isolation and gateway checks |
| P2-6 | Open PRs #1–#4 | **Merge them into `main` in order** (merge commits, because they are stacked), then branch Part 2 from `main` |
