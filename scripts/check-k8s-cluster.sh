#!/bin/bash
# Checks that the local Kubernetes cluster can host this POC, before any app is deployed:
#   1. it runs images built on this PC with `docker build` (no registry);
#   2. it enforces NetworkPolicy, both ingress and egress (the branch isolation depends on it);
#   3. a pod can reach your PC (host.docker.internal) unless an egress policy stops it.
# Works in its own namespaces (bdc-chk-*) and deletes them at the end.
# Run with the cluster up:  bash scripts/check-k8s-cluster.sh      Exit code 0 = every check holds.
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /dev/null and other paths

failures=0
PF_PORT=18099
pf_pid=

cleanup() {
  [ -n "$pf_pid" ] && kill "$pf_pid" 2>/dev/null
  kubectl delete namespace bdc-chk-srv bdc-chk-a bdc-chk-b --ignore-not-found --wait=false >/dev/null
}
trap cleanup EXIT

report() {  # report <ok|FAIL> <text>
  printf '  %-5s %s\n' "$1" "$2"
  [ "$1" = ok ] || failures=$((failures + 1))
}

# expect <from-namespace> <url> <reachable|blocked>: an HTTP request from the client pod in that namespace.
# Policies are applied asynchronously, so it retries for a few seconds before giving up.
expect() {
  local ns="$1" url="$2" want="$3" got code i
  for i in 1 2 3 4 5; do
    code=$(kubectl -n "$ns" exec client -- curl -s -m 3 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)
    if [ "$code" = 200 ]; then got=reachable; else got=blocked; fi
    [ "$got" = "$want" ] && break
    sleep 2
  done
  if [ "$got" = "$want" ]; then
    report ok "$(printf '%-11s -> %-46s %s' "$ns" "$url" "$got")"
  else
    report FAIL "$(printf '%-11s -> %-46s %s (expected %s)' "$ns" "$url" "$got" "$want")"
  fi
}

echo "Setting up: a server in bdc-chk-srv, a client in bdc-chk-a and in bdc-chk-b"
# Leftovers from an earlier run may still be terminating; a namespace can't be reused until it's gone.
kubectl delete namespace bdc-chk-srv bdc-chk-a bdc-chk-b --ignore-not-found --wait=true --timeout=120s >/dev/null
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: v1
kind: Namespace
metadata: { name: bdc-chk-srv }
---
apiVersion: v1
kind: Namespace
metadata: { name: bdc-chk-a }
---
apiVersion: v1
kind: Namespace
metadata: { name: bdc-chk-b }
---
apiVersion: v1
kind: Pod
metadata: { name: srv, namespace: bdc-chk-srv, labels: { app: srv } }
spec:
  containers:
    - name: srv
      image: alpine/socat:1.8.1.1
      args: ["TCP-LISTEN:8080,fork,reuseaddr", "SYSTEM:echo HTTP/1.0 200 OK; echo; echo ok"]
      ports: [{ containerPort: 8080 }]
---
apiVersion: v1
kind: Service
metadata: { name: srv, namespace: bdc-chk-srv }
spec:
  selector: { app: srv }
  ports: [{ port: 8080 }]
---
apiVersion: v1
kind: Pod
metadata: { name: client, namespace: bdc-chk-a }
spec:
  containers: [{ name: client, image: curlimages/curl:8.11.1, command: ["sleep", "3600"] }]
---
apiVersion: v1
kind: Pod
metadata: { name: client, namespace: bdc-chk-b }
spec:
  containers: [{ name: client, image: curlimages/curl:8.11.1, command: ["sleep", "3600"] }]
EOF
kubectl -n bdc-chk-srv wait --for=condition=Ready pod/srv --timeout=120s >/dev/null &&
  kubectl -n bdc-chk-a wait --for=condition=Ready pod/client --timeout=120s >/dev/null &&
  kubectl -n bdc-chk-b wait --for=condition=Ready pod/client --timeout=120s >/dev/null ||
  { echo "pods did not start"; exit 2; }
SRV=http://srv.bdc-chk-srv.svc:8080/

echo "1. An image built on this PC runs without a registry (unique tag, so a stale copy can't pass):"
tag="bdc-chk-local:$(date +%s)"
if printf 'FROM alpine/socat:1.8.1.1\nRUN echo %s > /marker\n' "$tag" | docker build -q -t "$tag" - >/dev/null &&
   kubectl -n bdc-chk-b run local-image --image="$tag" --restart=Never --command -- cat /marker >/dev/null &&
   kubectl -n bdc-chk-b wait --for=jsonpath='{.status.phase}'=Succeeded pod/local-image --timeout=60s >/dev/null &&
   [ "$(kubectl -n bdc-chk-b logs local-image)" = "$tag" ]; then
  report ok "pod ran $tag, built seconds ago"
else
  report FAIL "pod could not run the locally built $tag"
fi
docker image rm "$tag" >/dev/null 2>&1

echo "2. No policy yet: everything is open (the Kubernetes default):"
expect bdc-chk-a "$SRV" reachable
expect bdc-chk-b "$SRV" reachable
expect bdc-chk-b "http://example.com/" reachable   # control: the "internet blocked" checks below mean something

echo "3. Deny all ingress into bdc-chk-srv: nobody gets in:"
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-ingress, namespace: bdc-chk-srv }
spec:
  podSelector: {}
  policyTypes: [Ingress]
EOF
expect bdc-chk-a "$SRV" blocked
expect bdc-chk-b "$SRV" blocked

echo "4. Allow ingress from bdc-chk-a only: a gets in, b still doesn't:"
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-from-a, namespace: bdc-chk-srv }
spec:
  podSelector: { matchLabels: { app: srv } }
  policyTypes: [Ingress]
  ingress:
    - from: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: bdc-chk-a } } }]
      ports: [{ port: 8080 }]
EOF
expect bdc-chk-a "$SRV" reachable
expect bdc-chk-b "$SRV" blocked

echo "5. Your PC through a port-forward (port-forward traffic skips NetworkPolicy, so it reaches srv anyway):"
kubectl -n bdc-chk-srv port-forward svc/srv "$PF_PORT:8080" >/dev/null 2>&1 &
pf_pid=$!
disown "$pf_pid"   # no "Terminated" message when cleanup stops it
for i in $(seq 1 20); do curl -s -m 1 -o /dev/null "http://127.0.0.1:$PF_PORT/" && break; sleep 0.5; done
expect bdc-chk-b "http://host.docker.internal:$PF_PORT/" reachable

echo "6. Deny all egress from bdc-chk-a except DNS: a can't reach srv, the internet or your PC:"
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-egress, namespace: bdc-chk-a }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } },
             podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ port: 53, protocol: UDP }, { port: 53, protocol: TCP }]
EOF
expect bdc-chk-a "$SRV" blocked
expect bdc-chk-a "http://host.docker.internal:$PF_PORT/" blocked
expect bdc-chk-a "http://example.com/" blocked

echo "7. Allow egress from bdc-chk-a to srv only: srv works again, your PC and the internet stay blocked:"
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-to-srv, namespace: bdc-chk-a }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: bdc-chk-srv } },
             podSelector: { matchLabels: { app: srv } } }]
      ports: [{ port: 8080 }]
EOF
expect bdc-chk-a "$SRV" reachable
expect bdc-chk-a "http://host.docker.internal:$PF_PORT/" blocked
expect bdc-chk-a "http://example.com/" blocked

echo
if [ "$failures" -eq 0 ]; then
  echo "All checks hold."
else
  echo "$failures check(s) FAILED."
fi
exit "$failures"
