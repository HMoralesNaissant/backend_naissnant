#!/usr/bin/env bash
# Derives the per-tenant skaffolder config (configs/<TENANT_CONFIG_NAME>.yml) and exports the
# build variables skaffolder reads into the manifests' annotations and image, for the render step.
set -euo pipefail

# See generate-tenant-config.js for what changes per tenant. js-yaml is installed outside the
# checkout and found through NODE_PATH.
mkdir -p "${RUNNER_TEMP}/yaml"
(cd "${RUNNER_TEMP}/yaml" && npm init -y >/dev/null && npm install js-yaml@4.1.0 >/dev/null)
NODE_PATH="${RUNNER_TEMP}/yaml/node_modules" node source/.forgejo/scripts/generate-tenant-config.js
# Holds secret key *names* (the Infisical mapping), never values, so it is safe to print.
cat "skaffolder-configs/configs/${TENANT_CONFIG_NAME}.yml"

{
  echo "APP_IMAGE=${IN_IMAGE}"
  echo "BRANCH_NAME=${GITHUB_REF_NAME}"
  echo "REPO_NAME=${GITHUB_REPOSITORY#*/}"
  echo "COMMIT_SHA=${GITHUB_SHA}"
  echo "SHORT_SHA=${GITHUB_SHA::7}"
  echo "REPO_COMMIT_REF=https://${REGISTRY}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}"
} >> "${GITHUB_ENV}"
