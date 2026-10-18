# Scaling lab

Exercises you run yourself on the local cluster, with what to watch and what I measured on this PC (Windows 10,
4 cores / 8 threads, 16 GB, Docker Desktop's Kubernetes with 1 node). Phase 8 of the
[plan](../IMPLEMENTATION_PLAN.md#phase-8-scaling-lab).

- **This part (8a):** the branch service, scaled by a HorizontalPodAutoscaler (HPA) on CPU. Exercises 1 and 2.
- **Next (8b):** the listener, scaled by KEDA on Kafka lag, and the holiday schedule. Exercises 3 to 7.

## In short

| Question | Answer (measured) |
|---|---|
| What scales the branch service? | An HPA ([k8s/branch/base/hpa.yaml](../k8s/branch/base/hpa.yaml)): 1 to 3 copies, aiming for 70% of each copy's CPU request (200m) |
| How fast does it react? | Within ~15 s of the CPU rising it asks for more copies; they are ready ~25–30 s later. Back down after 2 minutes of low CPU, 1 copy a minute |
| Did 3 copies make it faster on this PC? | **No.** One warm copy answered 150 lookups/s with 0.2 of a core and 6 ms at the 95th percentile. 3 copies used 2.6 times the CPU for the same lookups |
| Why scale then? | On a real cluster the new copies run on **other machines** and add real capacity. The HPA works the same way there; this lab shows how it decides, not extra speed |
| What surprised me | A cold pod (after a restart) looked busy to the HPA because of JIT compiling, and each new copy needs ~2 cores for ~30 s to start: scaling up added load before it helped |

## Before you start

The stack is running (`node k8s/deploy.mjs`, `node k8s/port-forward.mjs`, see the [README](../README.md)).

**Park branch B** for the lab, to free memory for the extra copies. Pods ask the node for 92% of its memory with
everything running, which leaves room for one more branch copy. With B parked: 57%.

```bash
kubectl -n dc scale deploy/listener-b --replicas=0
kubectl -n branch-b scale deploy/branch-b sts/wso2-b sts/postgres-b --replicas=0
```

B's data stays on its volumes and events for B wait in Kafka. The dashboard shows B as unreachable, and the
port-forward script keeps restarting B's forward: both are expected. B's HPA does nothing while its Deployment
is at 0.

**Unpark it afterwards** (WSO2-B needs 2–3 minutes; the listener then delivers what waited):

```bash
kubectl -n branch-b scale sts/postgres-b sts/wso2-b deploy/branch-b --replicas=1
kubectl -n dc scale deploy/listener-b --replicas=1
```

Useful windows to keep open while you run the exercises:

```bash
kubectl get hpa -n branch-a -w                      # copies and CPU, as the HPA sees them
kubectl get pods -n branch-a -w                     # pods appearing and going
kubectl top pods -n branch-a                        # CPU and memory per pod (needs metrics-server)
kubectl get events -n branch-a --sort-by=.lastTimestamp | tail   # "New size: 3; reason: cpu ... above target"
```

`kubectl logs -l <label>` shows only the **last 10 lines of each pod**. Add `--tail=-1` to see everything (this
cost me a few minutes: a count of 144 deliveries came out as 30).

## Exercise 1: scale by hand

### 1a. Three listener copies share six partitions

```bash
kubectl -n dc scale deploy/listener-a --replicas=3
kubectl exec -n platform kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group listener-a   # who owns which partition, and the lag
```

In Git Bash, put `MSYS_NO_PATHCONV=1` in front of the `kubectl exec` line.

What I measured:

- The 2 new copies were ready after 24 s. About 40 s after the command every copy owned **2 of the 6
  partitions** (0+1, 3+4, 2+5).
- The hand-over was gradual (Kafka 4's consumer group protocol, KIP-848): the old copy gave up partitions 3–5
  first and 2 ten seconds later, and kept working on the others meanwhile. Its log says
  `Gave up partitions [3, 4, 5] (offsets committed): another consumer takes over`.
- A sync of A (144 events) was then delivered by all three, each only from its own partitions: 45, 48 and 51
  events. It took 4.4 s, no faster than one copy (~35 events/s), because the new copies were cold (JIT, a first
  token from WSO2) and 144 events are too few. Exercise 3 measures drain time with a real rush.
- Back to 1 (`--replicas=1`): Kubernetes removed the two newest copies; the old one took all 6 partitions back,
  lag 0.

A 7th copy would get no partition and sit idle: **partitions are the limit** (one consumer per partition per
group). Threads count the same way: in Spring Kafka every thread is a full consumer (exercise 7).

### 1b. Branch A by hand: the HPA takes it back

```bash
kubectl -n branch-a scale deploy/branch-a --replicas=3
kubectl get hpa -n branch-a -w
```

What I measured: 3 copies ready after 23 s; the HPA removed one at 141 s and the next at 203 s. With an HPA in
charge, the HPA decides: a number you set by hand lasts only until its next decision (here: after its 2-minute
scale-down window). To keep a number, change the HPA (`minReplicas`), not the Deployment. That is also why the
Deployment has no `replicas` field any more: otherwise every redeploy would reset it.

## Exercise 2: the HPA in a counter rush

### How the HPA decides

Every 15 s: **wanted copies = ceil(copies now × CPU now ÷ target)**, CPU as a percentage of the request (200m).
Example from the run below: 1 copy at 204% → ceil(1 × 204 ÷ 70) = 3.

- **Up:** at once (no waiting window), at most 2 copies per 30 s, never more than `maxReplicas: 3`.
- **Down:** only when every wish of the last 2 minutes was lower (the default is 5), then 1 copy a minute. One
  quiet minute in a rush must not remove a copy needed again a minute later.
- The CPU a new copy burns before it is ready (startup probe) is set aside: at 1:32 below the pods used 3.1 cores together, the HPA counted 132%, not 517%.

### Run it

```bash
node k8s/load-test.mjs                      # 40 lookups/s for 4 minutes, then waits for the scale-down
node k8s/load-test.mjs --rate 150 --hold 3m
```

The load test is a k6 Job **inside** branch A ([k8s/optional/load-test](../k8s/optional/load-test)): it
stands for the counter terminals at the checkpoint, looking up passports (`POST /passes/search`) through WSO2-A
with its own read-only WSO2 application (`counter-a`; it cannot get the sync scope: checked, 403 on
`POST /events`). It can't run through a port-forward: a port-forward always goes to one pod. Its two
NetworkPolicies open exactly "load test → WSO2-A" and are removed with it after the run.

Requests arrive at a fixed rate whether the system keeps up or not, like travellers. If it doesn't keep up, k6
reports `dropped_iterations` and the latency grows.

Every 15 s the script prints a line like this one (copies ready / wanted, the HPA's CPU, CPU per pod):

```text
 1:03  copies 1/3  hpa cpu 204%/70%  | branch-a-vsxr7 408m, counter-load-5q7t5 64m, wso2-a-0 441m
```

### What I measured (2026-10-18)

| Run | Copies | Branch CPU in the rush | Lookups/s p95 / p99 / max | Dropped | Failed |
|---|---|---|---|---|---|
| 40/s, HPA, **cold** pod (after a reboot) | 1 → 3 | 408m on 1 copy, then 150–390m on 3 | 12 ms / 164 ms / 2.09 s | 53 | 0 |
| 40/s, HPA pinned to 1 (`maxReplicas: 1`) | 1 | **58–95m** | 5.5 ms / 7 ms / 67 ms | 0 | 0 |
| 150/s, HPA, warm | 1 → 3 | ~205m on 1 copy, then 400–560m on 3 | 9.7 ms / 32 ms / 485 ms | 13 | 0 |
| 150/s, pinned to 1 | 1 | **~205m** steady | 5.8 ms / 34 ms / 1.1 s | 93 | 0 |

Timeline of the first run: CPU 82% at 0:48 → the HPA wants 2; 204% at 1:03 → wants 3; 2 ready at 1:18, 3 at 1:32.
k6 ended at 4:44; 3 → 2 at 5:48, 2 → 1 at 6:53.

### What it shows

1. **One warm copy was enough at both rates.** The branch pod has no CPU limit, so it uses as much of the node
   as it needs. 150 lookups/s cost it 0.2 of a core, on a PC with 8 threads. The HPA still scaled to 3 because
   its target is relative to the *request* (70% of 200m = 140m), not to the pod running out of CPU.
2. **A cold pod looks busy.** In the first run the pod had served almost nothing since the reboot. The JIT
   compiler's work at the start of the rush pushed it to 408m, the same pod later did the same rate with 60m.
   The HPA can't tell compiling from working.
3. **A new copy costs about 2 cores for ~30 s** (1.8–2.1 cores measured): Spring starting and the JIT compiling.
   On a node that is already busy, scaling up first adds load. That's when the slow requests (2.09 s) and most
   dropped lookups of the first run happened.
4. **Load is spread by connection, not by request.** WSO2 keeps its connections to the branch open, so one copy
   often did most of the work (506m next to two copies at ~60m), and the busy copy changed over time
   (probably as connections were closed and reopened; not checked). A Service balances connections, not requests.
5. **WSO2 costs more than the branch:** 220–850m for WSO2 (one sample 1.65 cores) against 60–210m for one warm
   branch copy at the same rates. In a
   real rush the gateways need scaling too (the platform team's job; here it is one pod per branch).

### What would make the copies count

- **Several nodes:** new copies land on other machines with their own cores (on one node they share the same 4).
- **A CPU limit per pod** (e.g. `limits: { cpu: 500m }`): then one copy really runs out at some rate, and more
  copies raise the total. This lab doesn't set one on purpose: a CPU limit also slows Java's startup and JIT
  (the 2 cores above), so every restart and scale-up gets slower. Try it yourself (not measured):
  `kubectl -n branch-a patch deploy branch-a --type json -p '[{"op":"add","path":"/spec/template/spec/containers/0/resources/limits/cpu","value":"300m"}]'`,
  then `node k8s/load-test.mjs --rate 150`. Undo with `node k8s/deploy.mjs --no-build`.
- **Warm before the rush:** start the copies before a known peak (the holiday schedule, exercise 4 in 8b), so
  their 30 s of startup and JIT happen before the travellers arrive.

### Try it yourself

```bash
kubectl -n branch-a patch hpa branch-a --type merge -p '{"spec":{"maxReplicas":1}}'   # pin to 1 copy
node k8s/load-test.mjs --rate 150 --hold 3m --no-wait-down
kubectl -n branch-a patch hpa branch-a --type merge -p '{"spec":{"maxReplicas":3}}'   # back to normal
node k8s/load-test.mjs --rate 300                                                     # where does it break?
```

If you lift the pin right after a run, the HPA still acts on the last CPU sample of the run: I saw it add a copy
40 s after the load had stopped, and remove it 2 minutes later.

## Clean up

- Unpark branch B (above).
- A run removes the load test itself; after `--keep`: `kubectl delete -k k8s/optional/load-test`.
- A pinned HPA: `kubectl -n branch-a patch hpa branch-a --type merge -p '{"spec":{"maxReplicas":3}}'`.
