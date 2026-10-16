#!/usr/bin/env bash
# Derives the per-tenant skaffolder config, then renders it to manifests with skaffolder + skaffold.
set -euo pipefail

# --- Per-tenant config -------------------------------------------------------------------------
# See generate-tenant-config.js for what changes per tenant. js-yaml is installed outside the
# checkout and found through NODE_PATH.
mkdir -p "${RUNNER_TEMP}/yaml"
(cd "${RUNNER_TEMP}/yaml" && npm init -y >/dev/null && npm install js-yaml@4.1.0 >/dev/null)
NODE_PATH="${RUNNER_TEMP}/yaml/node_modules" node source/.forgejo/scripts/generate-tenant-config.js
# Holds secret key *names* (the Infisical mapping), never values, so it is safe to print.
cat "skaffolder-configs/configs/${TENANT_CONFIG_NAME}.yml"

# --- Manifests ---------------------------------------------------------------------------------
# Read by skaffolder into the manifests' annotations and image.
export APP_IMAGE="${IN_IMAGE}"
export BRANCH_NAME="${GITHUB_REF_NAME}"
export REPO_NAME="${GITHUB_REPOSITORY#*/}"
export COMMIT_SHA="${GITHUB_SHA}"
export SHORT_SHA="${GITHUB_SHA::7}"
export REPO_COMMIT_REF="https://${REGISTRY}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}"
skaffolder init --name "${TENANT_CONFIG_NAME}" --env "${IN_ENV}" --schema "${SCHEMA}" \
  --schemas-dir skaffolder-configs/schemas \
  --configs-dir skaffolder-configs/configs \
  --output-dir output \
  --v
ls output output/manifests

skaffold render --offline --profile="${IN_ENV}" --namespace "${IN_NAMESPACE}" \
  --output output/manifest.yaml --filename=output/skaffold.yaml
