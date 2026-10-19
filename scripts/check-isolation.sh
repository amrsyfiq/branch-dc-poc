#!/bin/bash
# Checks the network rules between the "environments" (namespaces) on Kubernetes by opening TCP connections
# from inside the pods. Run with the stack deployed (and, for the "your PC" rules to mean something,
# node k8s/port-forward.mjs running):  bash scripts/check-isolation.sh
# Exit code 0 = every rule holds.
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /dev/tcp paths

failures=0

# expect <namespace/workload> <host> <port> <reachable|blocked>
expect() {
  local from="$1" host="$2" port="$3" want="$4" got
  if kubectl exec -n "${from%%/*}" "${from#*/}" -- bash -c "timeout 3 bash -c 'exec 3<>/dev/tcp/$host/$port'" 2>/dev/null; then
    got=reachable
  else
    got=blocked
  fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-28s -> %-38s %s\n' "$from" "$host:$port" "$got"
  else
    printf '  FAIL  %-28s -> %-38s %s (expected %s)\n' "$from" "$host:$port" "$got" "$want"
    failures=$((failures + 1))
  fi
}

BA=branch-a/deploy/branch-a; BB=branch-b/deploy/branch-b
LA=dc/deploy/listener-a; LB=dc/deploy/listener-b; DC=dc/deploy/dc-service
WA=branch-a/sts/wso2-a; WB=branch-b/sts/wso2-b
KAFKA=kafka-0.kafka.platform.svc.cluster.local

echo "Branches cannot reach the DC environment, Kafka or the other branch:"
expect $BA dc-service.dc 8090 blocked
expect $BA $KAFKA 9092 blocked
expect $BA branch-b.branch-b 8080 blocked
expect $BA wso2-b.branch-b 8245 blocked
expect $BA postgres-dc.dc 5432 blocked
expect $BA postgres-b.branch-b 5432 blocked
expect $BB dc-service.dc 8090 blocked
expect $BB $KAFKA 9092 blocked
expect $BB branch-a.branch-a 8080 blocked

echo "Not just DNS: the DC's and Kafka's pod IPs are unreachable from a branch too:"
dc_ip=$(kubectl get pod -n dc -l app.kubernetes.io/name=dc-service -o jsonpath='{.items[0].status.podIP}')
kafka_ip=$(kubectl get pod -n platform kafka-0 -o jsonpath='{.status.podIP}')
expect $BA "$dc_ip" 8090 blocked
expect $BA "$kafka_ip" 9092 blocked

echo "Nor your PC (the port-forwards to the DC and its database) or the internet:"
expect $BA host.docker.internal 8090 blocked
expect $BA host.docker.internal 5433 blocked
expect $BA example.com 80 blocked

echo "Each listener reaches Kafka and its own branch's WSO2 (gateway and token endpoint) only:"
expect $LA $KAFKA 9092 reachable
expect $LA wso2-a 8244 reachable
expect $LA wso2-a 9444 reachable
expect $LA wso2-b.branch-b 8245 blocked
expect $LA branch-a.branch-a 8080 blocked
expect $LA postgres-a.branch-a 5432 blocked
expect $LA postgres-dc 5432 blocked
expect $LA dc-service 8090 blocked
expect $LB $KAFKA 9092 reachable
expect $LB wso2-b 8245 reachable
expect $LB wso2-a.branch-a 8244 blocked
expect $LB branch-b.branch-b 8080 blocked

echo "WSO2 sits inside its branch environment: it reaches its branch, nothing else:"
expect $WA branch-a 8080 reachable
expect $WA dc-service.dc 8090 blocked
expect $WA $KAFKA 9092 blocked
expect $WA branch-b.branch-b 8080 blocked
expect $WA postgres-a 5432 blocked
expect $WA host.docker.internal 8090 blocked
expect $WA example.com 80 blocked
expect $WB branch-b 8080 reachable
expect $WB dc-service.dc 8090 blocked

echo "The DC reaches its database and Kafka, not the branches:"
expect $DC postgres-dc 5432 reachable
expect $DC $KAFKA 9092 reachable
expect $DC wso2-a.branch-a 8244 blocked
expect $DC branch-a.branch-a 8080 blocked
expect $DC postgres-a.branch-a 5432 blocked

echo "Each database is reachable by its own service only:"
expect $BA postgres-a 5432 reachable
expect $BB postgres-b 5432 reachable

echo "Inside a branch namespace, an unknown pod reaches nothing (being in the namespace is not enough):"
# probe <namespace> <labels|-> <host> <port> <want>: a throwaway pod with only these labels (- = none). Being in a
# namespace, or wearing an app's labels, is all a pod has: the NetworkPolicies decide by namespace and labels.
probe() {
  local ns="$1" labels="$2" host="$3" port="$4" want="$5" got who
  local extra=()
  if [ "$labels" = "-" ]; then who="$ns/(unknown pod)"; else who="$ns/($labels)"; extra=(--labels="$labels"); fi
  if kubectl run -n "$ns" "probe-$RANDOM" --image=curlimages/curl:8.11.1 --restart=Never --rm -i -q "${extra[@]}" \
      --overrides='{"spec":{"containers":[{"name":"c","image":"curlimages/curl:8.11.1","command":["sh","-c","nc -z -w 3 '"$host"' '"$port"'"],"resources":{"limits":{"memory":"32Mi"},"requests":{"memory":"32Mi"}}}]}}' \
      >/dev/null 2>&1; then got=reachable; else got=blocked; fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-28s -> %-38s %s\n' "$who" "$host:$port" "$got"
  else
    printf '  FAIL  %-28s -> %-38s %s (expected %s)\n' "$who" "$host:$port" "$got" "$want"
    failures=$((failures + 1))
  fi
}
probe branch-a - kube-dns.kube-system 53 reachable   # control: DNS is open to every pod, so "blocked" below means blocked
probe branch-a - branch-a 8080 blocked
probe branch-a - postgres-a 5432 blocked
probe branch-a - wso2-a 8244 blocked

echo "KEDA reads the listeners' lag: only its operator reaches Kafka, and nothing else of ours:"
probe keda app=keda-operator kafka-bootstrap.platform 9092 reachable
probe keda app=keda-operator $KAFKA 9092 reachable
probe keda - $KAFKA 9092 blocked   # any other pod in KEDA's namespace
probe keda app=keda-operator wso2-a.branch-a 8244 blocked
probe keda app=keda-operator branch-a.branch-a 8080 blocked
probe keda app=keda-operator postgres-dc.dc 5432 blocked

echo
if [ "$failures" -eq 0 ]; then echo "All rules hold."; else echo "$failures rule(s) broken."; fi
exit "$failures"
