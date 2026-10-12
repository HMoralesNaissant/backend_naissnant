#!/usr/bin/env node
// Derives the per-tenant skaffolder config used by tenant-deploy_v1.yml.
//
// Reads skaffolder-configs/configs/<PROJECT>.yml (the app's own platform config) and writes
// skaffolder-configs/configs/<TENANT_CONFIG_NAME>.yml with only what is per-tenant changed: the k8s
// name, the namespace, where Infisical is read from, and the URLs the two apps use to find each
// other. Everything else (resources, probes, secret mappings) is inherited, so a change to the
// platform config reaches tenants on their next deploy.
//
// Inputs come from the environment, never argv, so the workflow can pass them without interpolating
// them into a shell command line. js-yaml is resolved through NODE_PATH (the workflow installs it).

const fs = require("fs");
const yaml = require("js-yaml");

const REQUIRED = [
  "PROJECT",
  "TENANT_CONFIG_NAME",
  "IN_ENV",
  "IN_SLUG",
  "IN_NAMESPACE",
  "IN_APP",
  "IN_APP_NAME",
  "IN_BACKEND_NAME",
  "IN_UI_NAME",
];

/** Returns a copy of the app's config, specialised for one tenant. */
function buildTenantConfig(cfg, platform, e) {
  const out = structuredClone(cfg);
  const domain = platform[e.IN_ENV]?.domain ?? platform.defaults.domain;
  const host = (name) => `https://${name}-${e.IN_ENV}.${domain}`;

  out.defaults ??= {};
  out.defaults.name = e.IN_APP_NAME;
  out.defaults.description = `${out.defaults.description ?? e.PROJECT} (${e.IN_SLUG})`;
  out.defaults.infisical = {
    ...(out.defaults.infisical ?? {}),
    secret_path: `/${e.IN_SLUG}/${e.IN_APP}`,
  };

  const envCfg = (out[e.IN_ENV] ??= {});
  envCfg.namespace = e.IN_NAMESPACE;
  envCfg.env_variables ??= {};
  if (e.IN_APP === "backend") {
    envCfg.env_variables.CORS_ORIGINS = host(e.IN_UI_NAME);
    envCfg.env_variables.REDIS_KEY_PREFIX = `tenantos:${e.IN_ENV}:${e.IN_SLUG}`;
  } else {
    envCfg.env_variables.TENANT_BACKEND_URL = host(e.IN_BACKEND_NAME);
  }
  return out;
}

function main(e = process.env) {
  const missing = REQUIRED.filter((key) => !e[key]);
  if (missing.length) {
    console.error(`Missing required environment variable(s): ${missing.join(", ")}`);
    process.exit(1);
  }

  const dir = e.CONFIGS_DIR || "skaffolder-configs/configs";
  const load = (file) => yaml.load(fs.readFileSync(`${dir}/${file}`, "utf8")) ?? {};

  const tenantConfig = buildTenantConfig(load(`${e.PROJECT}.yml`), load("default.yml"), e);
  fs.writeFileSync(`${dir}/${e.TENANT_CONFIG_NAME}.yml`, yaml.dump(tenantConfig));
}

if (require.main === module) {
  main();
}

module.exports = { buildTenantConfig };
