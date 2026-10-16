#!/usr/bin/env bash
# Validates the tenant-deploy_v1 dispatch inputs and exports PROJECT/SCHEMA for later steps.
# Inputs arrive as IN_* env vars, never interpolated into a command line: they come from the
# registrar, but a slug or name is still attacker-influenced text.
set -euo pipefail

fail() { echo "::error::$1"; exit 1; }

label='^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
[[ "${IN_ENV}" =~ ^(dev|staging|prod)$ ]] || fail "bad env"
[[ "${IN_APP}" =~ ^(backend|ui)$ ]] || fail "bad app"
for v in "${IN_SLUG}" "${IN_NAMESPACE}" "${IN_APP_NAME}" "${IN_BACKEND_NAME}" "${IN_UI_NAME}"; do
  [[ "${v}" =~ ${label} ]] || fail "not a DNS label: ${v}"
done
[[ "${IN_NAMESPACE}" == tenant-* ]] || fail "namespace must be tenant-*"
# Free text, so only bounded and single-line: it reaches the app as one env value.
[[ -n "${IN_TENANT_NAME}" && ${#IN_TENANT_NAME} -le 200 && "${IN_TENANT_NAME}" != *$'\n'* ]] \
  || fail "bad tenant name"
[[ "${IN_IMAGE}" =~ ^[A-Za-z0-9./:@_-]+$ ]] || fail "bad image ref"

# backend -> tenantos-backend, ui -> tenantos-ui; both render with the nodejs schema.
{
  echo "PROJECT=tenantos-${IN_APP}"
  echo "SCHEMA=nodejs"
} >> "${GITHUB_ENV}"
