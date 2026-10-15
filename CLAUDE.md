# Branch–DC Event-Driven POC

Context for Claude Code sessions in this folder. It was written at the end of a long session that built the sibling project `../it-access-portal` (Spring Boot + Oracle DB + Kafka + Angular + WSO2 API Manager). Read this file fully before starting, then begin with **Phase 0** (inspect, plan, write `IMPLEMENTATION_PLAN.md`, and get the user's review before writing code).

## 1. Goal

A proof of concept of a **data centre (DC) distributing data to branches** with an **Event-Driven Architecture**, Apache Kafka as the event broker, and **WSO2 API Manager controlling access** into each branch.

- The DC holds the data of **all** branches (seeded).
- Each branch holds **only its own** data. Branch A must never receive branch B's data.
- The flow is **one-way: DC → branches**. Branches never call or send anything to the DC.

## 2. Target architecture (agreed with the user)

```text
┌──────────────────────── DC environment ─────────────────────────────┐      ┌──── Branch A environment ────┐
│                                                                     │      │                              │
│  DC service                     topic dc.branch-A ┌───────────────┐ │      │ ┌──────────┐    ┌──────────┐ │
│  GET  /data   (all branches)   ┌─────────────────►│ Listener A    │─┼──────┼►│ WSO2     │───►│ Branch A │ │
│  POST /sync/{branch}           │                  └───────────────┘ │      │ │ gateway  │    │ GET /data│ │
│  POST /purge/{branch}          │                                    │      │ └──────────┘    │ (A only) │ │
│        │                       │                                    │      │                 └──────────┘ │
│        ▼                ┌──────┴──────┐                             │      └──────────────────────────────┘
│  DC database  ─────────►│    Kafka    │                             │      ┌──── Branch B environment ────┐
│  (all data)     publish └──────┬──────┘                             │      │                              │
│                                │  topic dc.branch-B ┌─────────────┐ │      │ ┌──────────┐    ┌──────────┐ │
│                                └───────────────────►│ Listener B  │─┼──────┼►│ WSO2     │───►│ Branch B │ │
│                                                     └─────────────┘ │      │ │ gateway  │    │ GET /data│ │
│                                                                     │      │ └──────────┘    │ (B only) │ │
└─────────────────────────────────────────────────────────────────────┘      │                 └──────────┘ │
                                                                             └──────────────────────────────┘

One-way flow: DC ──► Kafka ──► Listener (in DC env) ──► WSO2 (in branch env) ──► Branch.  Branches never call the DC.
```

Saved copies: [docs/chart.txt](docs/chart.txt) (the user's own file: an early "Ideas" chart, now **superseded** because it had branches publishing to the DC, followed by the **corrected** one-way version, which is the chart above), [docs/architecture-chart.md](docs/architecture-chart.md) (the corrected chart plus the user's notes), [docs/architecture-sketch.jpg](docs/architecture-sketch.jpg) (the user's drawing) and [docs/eda-concept.png](docs/eda-concept.png) (the EDA concept image). The user's own drawing showed the same thing: the DC environment box contains DC → Kafka → "Listener Branch A" / "Listener Branch B"; each branch environment box contains WSO2 → Branch.

### Behaviour (confirmed by the user)

| Site                   | Endpoint               | Behaviour                                                                                               |
| ---------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------- |
| DC, branch A, branch B | `GET /data`            | Every site has the same read endpoint. The DC returns all branches' data; a branch returns only its own |
| DC only                | `POST /sync/{branch}`  | Publishes **only that branch's slice** of the DC data to the branch's topic; the branch stores a copy   |
| DC only                | `POST /purge/{branch}` | Publishes a purge command to the branch's topic; the branch deletes its data                            |
| DC                     | (seed)                 | DC data is **seeded** (script or migration), not created by branches                                    |

- **Listeners live in the DC environment**, one per branch, each reading only its own topic, and deliver to the branch **through that branch's WSO2 gateway** (HTTP, with an OAuth2 token and scope).
- **The branch application is the same code** deployed once per branch ("same service, different location"), configured with its branch ID and its own database.
- **"Environments" (the boxes):** simulate them with separate Docker networks (`dc-net`, `branch-a-net`, `branch-b-net`). Each listener joins `dc-net` and its own branch network; branches cannot reach `dc-net`. That makes the one-way rule real, not just a convention.

### Key concepts to keep straight (from the discussion)

- **EDA:** producers publish events to the broker; consumers subscribe; neither knows the other.
- **Isolation must be enforced, not assumed:** a listener "choosing" one topic is not security. Enforce it with WSO2 (each branch's API/scope only accepts its own listener's client) and, ideally, Kafka ACLs per listener.
- **WSO2 API Manager is not in the Kafka traffic path.** Services talk to Kafka in Kafka's protocol. WSO2 governs the **HTTP entry into each branch**: token, scope, and optionally rate limits. Its portals: **Publisher** (API owners define and publish APIs), **Developer Portal** (consumers subscribe and get keys), **Admin** (policies, key managers). The login/token service is the built-in **Key Manager** ("Resident Key Manager", port 9443).
- **Real-world note:** a real multi-site setup usually has a Kafka cluster per site, replicated with MirrorMaker 2. For this POC one Kafka simulating all sites is fine. State this as a simplification.

## 3. Open decisions (ask the user in Phase 0)

1. **WSO2 per branch:** one WSO2 instance with a separate API per branch (e.g. `/branch-a/...`, `/branch-b/...`, each with its own scope and subscribed client) is **recommended**, because each WSO2 needs ~2 GB of RAM. The alternative is two real WSO2 instances, one per branch environment: closest to the diagram, but likely too heavy (see section 5).
2. **What the data is:** e.g. products and prices, customers, inventory? This drives the schema and the events.
3. **Database:** PostgreSQL per site (recommended: light, and makes "each site has its own data" obvious) vs Oracle (the user's other project used Oracle Free 23ai; heavy to run three times).
4. **UI:** none (API + scripts + curl), or a small dashboard showing DC and branch data side by side, live?
5. **Sync semantics:** a full replace of the branch's slice on every sync (simplest, idempotent) vs incremental upserts.
6. **Number of branches:** 2 (A and B), unless the user wants more.
7. **GitHub:** a new repository? The user's account is `amrsyfiq`; their previous repo is public.

## 4. Suggested stack and patterns (proven in ../it-access-portal)

Reuse these as _patterns_ (copy and adapt; don't fork the old project):

- **Java 21, Spring Boot 4.1.x, Maven wrapper.** Spring for Apache Kafka 4.1 (Kafka client 4.2).
- **Persistence: Spring Data JPA (Hibernate)**, decided by the user; **no MyBatis** in this POC (unlike `../it-access-portal`). Use Flyway (or Liquibase) for the schema rather than `ddl-auto=update`, so the schema stays explicit and versioned. Keep JPA entities out of the API: return DTOs.
- **Kafka (apache/kafka:4.2.2, KRaft, single node) in docker-compose.** Topics created by `NewTopic` beans (auto-create off); JSON events with Jackson; record **key** = entity/branch id for ordering; `ErrorHandlingDeserializer`; `DefaultErrorHandler` with retries then a dead-letter topic (`DeadLetterPublishingRecoverer`, explicit DLT name); **idempotent consumers** (unique event id); publish **after DB commit** with `@TransactionalEventListener(AFTER_COMMIT)`. Known gap: no outbox, so an event is lost if the app dies between commit and send.
- **WSO2 API Manager 4.7.0 (`wso2/wso2am:4.7.0`)** under a compose profile, with a mounted `deployment.toml` and a **scripted, idempotent setup** (`setup.mjs` using the Publisher v4 / Developer Portal v3 REST APIs; users and roles via the `RemoteUserStoreManagerService` SOAP admin service, because SCIM2 did not work with the available credentials). WSO2 quirks learned the hard way:
  - 4.7 tries to write an `[encryption] key` into a read-only `deployment.toml`: provide the key from `.env`.
  - The client **secret is shown only once**; later API reads return it masked (`5f4****`). Save it on the first run and never overwrite it.
  - With JWT access tokens, the backend JWT (`X-JWT-Assertion`) carries the user **UUID**, not the username; `include_username_in_access_token` breaks token issuing (HTTP 500).
  - CORS for `:9443` could not be enabled via `deployment.toml`; use a dev proxy instead.
  - For machine-to-machine (listener → branch), use the **client-credentials** grant with a scope per branch.
  - Backend verification of the gateway JWT: nimbus-jose-jwt, RS256 only, issuer/expiry checked, with WSO2's exported certificate.
- **Tests:** JUnit 6 + Testcontainers (real Kafka and real DB; no H2); `@Testcontainers(disabledWithoutDocker = true)`. Always check the summary says **`Skipped: 0`**; Docker Desktop does not start automatically after a reboot. Do mutation checks for security tests. After a mutation check, run **`mvnw clean`**: a stale compiled class once looked like a real bug for an hour.
- **Secrets:** a git-ignored `.env` created by a `node infra/init-env.mjs` script (random values, correct formats); `.env.example` holds placeholders only.

## 5. Environment (as of 2026-10-14)

- Windows 10, 16 GB RAM, 8 CPUs. Docker Desktop (WSL2) with ~8 GB for containers. JDK 21 (Temurin 21.0.12) at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. Node 22.23. GitHub CLI at `C:\Program Files\GitHub CLI\gh.exe`, logged in as `amrsyfiq`. Git identity for repos: `Amir Syafiq <amir.syafiq989@gmail.com>` (set per repo). Python is **not** installed: use Node for scripts.
- **The old project's containers may still be running** and use ports 1521, 9092, 1025/8025, 8080, 4200, 8081, 9443/8243/8280: `portal-oracle`, `portal-kafka`, `portal-mailpit`, `portal-kafka-ui`, `portal-wso2am`. Check with `docker ps`, and stop them (`docker compose --profile gateway --profile ui stop` in `../it-access-portal`) or pick other ports before starting this POC. Their combined memory use (Oracle ~2.2 GB, WSO2 ~1.4 GB, Kafka ~0.6 GB) matters for the WSO2-per-branch decision.

## 6. About the user (how to work with them)

- Learning Spring Boot after other backend frameworks (Laravel-style comparisons help). Wants **short explanations after working code**, not long lectures beforehand.
- Works **phase by phase** with PRs; asks for "merge" explicitly; occasionally asks to commit small changes straight to `main`.
- Values honesty: report skipped tests, unverified parts and mistakes plainly. Never claim something works without running it.
- Answers in plain English work best; the user sometimes writes notes in Malay.
