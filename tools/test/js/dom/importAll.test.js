/**
 * Smoke gate: every project JS module must import cleanly under Node with the
 * browser shim installed. This is the harness's whole-corpus coverage: any new .js
 * file under apps/ is picked up automatically, and a regression that breaks parsing,
 * module resolution, or import-time evaluation fails the build.
 *
 * Each file is evaluated in its own child process (helpers/runOne.mjs) so modules
 * get pristine globals, cannot pollute each other, and a wedged import dies by
 * timeout instead of hanging the suite. Vendored third-party bundles are excluded
 * below, each with a reason.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readdir } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = new URL(".", import.meta.url);
const appsRoot = fileURLToPath(new URL("../../../../apps/", here));
const loader = fileURLToPath(new URL("../helpers/loader.mjs", here));
const runOne = fileURLToPath(new URL("../helpers/runOne.mjs", here));

/**
 * linkedom powers both the smoke children's shim and this orchestrator's fixture
 * needs; when npm dependencies have not been installed (offline CI, fresh clone),
 * the whole gate skips with a reason instead of failing the build.
 */
let linkedomAvailable = true;
try {
  await import("linkedom");
} catch {
  linkedomAvailable = false;
}
const SKIP_REASON = "linkedom not installed; run `npm install` in tools/test/js";

/** Per-file evaluation timeout in ms; killed children count as failures */
const TIMEOUT_MS = 10_000;
/** Parallel child processes; Node startup dominates, so modest parallelism is plenty */
const CONCURRENCY = 8;

/**
 * Paths (relative to apps/, trailing / marks directories) skipped by the smoke
 * gate, each with a reason. Keep this list short: exclusions are coverage holes.
 */
const EXCLUSIONS = [
  {
    path: "routerconsole/jsp/js/iframeResizer/",
    reason: "vendored third-party iframe sizing library (not our code)"
  },
  {
    path: "routerconsole/jsp/js/geomap.js",
    reason: "SVG world map: linkedom cannot execute its image/observer DOM internals"
  }
];

function isExcluded(relPath) {
  return EXCLUSIONS.some(ex =>
    ex.path.endsWith("/") ? relPath.startsWith(ex.path) : relPath === ex.path
  );
}

async function collectJsFiles(dir, out) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name === "node_modules") {continue;}
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      await collectJsFiles(full, out);
    } else if (entry.name.endsWith(".js")) {
      out.push(full);
    }
  }
  return out;
}

/**
 * Runs one target file in a child process and resolves when it evaluates cleanly.
 *
 * @param {string} file - absolute path to the JS file
 * @returns {Promise<void>} rejects with a descriptive error on failure
 */
function smokeOne(file) {
  return new Promise((resolvePromise, rejectPromise) => {
    execFile(
      process.execPath,
      ["--import", loader, runOne, file],
      { timeout: TIMEOUT_MS, maxBuffer: 4 * 1024 * 1024 },
      (error, _stdout, stderr) => {
        if (!error) {return resolvePromise();}
        const tail = String(stderr || "").trim().split("\n").slice(-6).join("\n");
        const why = error.killed ? `timed out after ${TIMEOUT_MS}ms` : tail || error.message;
        rejectPromise(new Error(why));
      }
    );
  });
}

test("smoke corpus integrity", { skip: linkedomAvailable ? false : SKIP_REASON }, async () => {
  const all = await collectJsFiles(appsRoot, []);
  assert.ok(all.length >= 100, `unexpectedly small corpus: ${all.length}`);
});

test(
  "every project JS file imports cleanly under the browser shim",
  { concurrency: CONCURRENCY, skip: linkedomAvailable ? false : SKIP_REASON },
  async t => {
    const all = await collectJsFiles(appsRoot, []);
    const rel = f => relative(appsRoot, f).split(sep).join("/");
    const targets = all.filter(f => !isExcluded(rel(f)));
    const excluded = all.filter(f => isExcluded(rel(f)));
    // Every exclusion must still match something, or its path went stale.
    for (const ex of EXCLUSIONS) {
      const hit = excluded.some(f => rel(f) === ex.path || rel(f).startsWith(ex.path));
      assert.ok(hit, `exclusion matches no file anymore: ${ex.path}`);
    }
    for (const file of targets) {
      t.test(relative(appsRoot, file).split(sep).join("/"), { timeout: TIMEOUT_MS + 5_000 }, () =>
        smokeOne(file)
      );
    }
  }
);
