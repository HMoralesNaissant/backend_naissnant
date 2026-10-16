#!/usr/bin/env bash
# Copies the registry pull secret from the platform namespace into the tenant's.
#
# The templates give every Deployment imagePullSecrets, and the registrar creates the namespace
# empty - without this the pods sit in ImagePullBackOff. Copying from the platform namespace keeps
# one credential to rotate, not one per tenant.

# What the script does (.forgejo/scripts/copy-pull-secret.sh)
# 1. It reads the forgejo-registry Secret from the platform namespace, tenant-apps-<env>, where the platform's own apps already use it.
# 2. It rewrites the metadata to the tenant's namespace. That drops fields like resourceVersion and uid that would make the apply fail.
# 3. It pipes the result to kubectl apply, so running it again is harmless.
set -euo pipefail

kubectl get secret "${PULL_SECRET_NAME}" -n "${PLATFORM_NAMESPACE}" -o json \
  | python3 -c '
import json, sys
s = json.load(sys.stdin)
s["metadata"] = {"name": s["metadata"]["name"], "namespace": sys.argv[1]}
json.dump(s, sys.stdout)
' "${IN_NAMESPACE}" \
  | kubectl apply -f -
