/**
 * @file setup.mjs - Dependency bootstrap for the i2p+ JS test harness.
 *
 * Invoked by the ant test-js targets before the suite runs. Installs or refreshes
 * node_modules whenever it is missing or older than package.json/package-lock.json,
 * using `npm ci` so stale packages from renamed dependencies are always replaced
 * with a tree that exactly matches the lockfile.
 *
 * Degradation contract: if npm is unavailable or the install fails (offline, no
 * network), this exits 0 with a notice — suites needing linkedom skip themselves,
 * so offline runs stay green. Delete node_modules/ (or bump the lockfile) to force
 * a reinstall.
 *
 * Exit codes: 0 = ready (or degraded-but-usable); 1 = unexpected internal error.
 *
 * @license AGPL3 or later
 */

import { spawnSync } from "node:child_process";
import { existsSync, statSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const harnessDir = join(here, "..");
const pkg = join(harnessDir, "package.json");
const lock = join(harnessDir, "package-lock.json");
const modules = join(harnessDir, "node_modules");
const stamp = join(modules, ".install-ok");

/** Newest mtime among the manifest files; 0 when neither exists. */
function manifestMtime() {
  let newest = 0;
  for (const file of [pkg, lock]) {
    if (existsSync(file)) {
      newest = Math.max(newest, statSync(file).mtimeMs);
    }
  }
  return newest;
}

function isStale() {
  if (!existsSync(modules) || !existsSync(stamp)) {return true;}
  const installed = statSync(stamp).mtimeMs;
  return installed < manifestMtime();
}

function install() {
  console.log("[node_modules] installing fresh from lockfile...");
  const result = spawnSync("npm", ["ci", "--no-audit", "--no-fund"], {
    cwd: harnessDir,
    stdio: "inherit",
    shell: process.platform === "win32",
    // npm itself emits noisy runtime deprecation warnings on some versions;
    // they are build output cruft here, not harness diagnostics.
    env: { ...process.env, NODE_OPTIONS: [process.env.NODE_OPTIONS, "--no-warnings"].filter(Boolean).join(" ") }
  });
  return result.status === 0;
}

try {
  if (!isStale()) {
    console.log("[node_modules] up to date");
    process.exit(0);
  }
} catch (error) {
  // A vanished racy node_modules just means "stale"; anything else is fatal.
  console.error(`[node_modules] check failed: ${error && error.message}`);
  process.exit(1);
}

if (!install()) {
  console.warn("[node_modules] npm ci failed; continuing without dependencies.");
  console.warn("[node_modules] suites requiring linkedom will skip themselves.");
  process.exit(0);
}

writeFileSync(stamp, new Date().toISOString());
console.log("[node_modules] ready");
