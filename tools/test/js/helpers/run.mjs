/**
 * @file run.mjs - Test runner and formatter for the i2p+ JS harness.
 *
 * Replaces the raw `node --test` invocation from ant. Uses the stable programmatic
 * node:test API so output formatting is fully under our control across supported
 * Node versions (>= 20.6), instead of depending on the CLI spec reporter's layout
 * (per-test durations, ℹ summary blocks, inconsistent suite indentation).
 *
 * All suites execute in-process (isolation "none"), matching the old single-child
 * `node --test` model exactly — the smoke gate already spawns fresh child processes
 * of its own wherever pristine globals actually matter. Child-process mode was not
 * an option: current Node releases stream only file-level rollups to the parent,
 * hiding every individual result.
 *
 * Output contract: one aligned dot-leader line per test file, two-space indentation
 * throughout, failure details printed beneath their file's line, and a single totals
 * line. Output is held until the whole run ends so listing order is deterministic.
 * Exit code 1 when anything failed.
 *
 * Usage: node run.mjs [file.test.js ...]   (defaults to ./suite.test.js)
 *
 * @license AGPL3 or later
 */

import { register } from "node:module";
import { run } from "node:test";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const harnessDir = join(here, "..");
// helpers -> js -> test -> tools -> repo root
const repoRoot = join(harnessDir, "..", "..", "..");

// Install the module-resolution hook unless the caller already did via --import.
if (!globalThis.__i2pJsHarnessHooked) {
  globalThis.__i2pJsHarnessHooked = true;
  register("./resolveHook.mjs", import.meta.url);
}

const LABEL_WIDTH = 46;

/** Wall-clock duration source for the totals line. */
const startedAt = Date.now();

const files = process.argv.length > 2
  ? process.argv.slice(2)
  : [join(harnessDir, "suite.test.js")];

/**
 * Renders a test file path as a repo-relative label ("dom/importAll.test.js").
 *
 * @param {?string} file - path of the file owning a test (absolute or relative)
 * @param {string} fallback - test name to use when no file is attributed
 * @returns {string} display label
 */
function labelFor(file, fallback) {
  if (!file) {return fallback;}
  const rel = relative(repoRoot, file);
  return rel || fallback;
}

/**
 * Fixed-width dot-leader padding between a label and its count column.
 *
 * @param {string} label - left-hand text
 * @param {string} suffix - right-hand summary ("12 passed")
 * @returns {string} single formatted line
 */
function leaderLine(label, suffix) {
  let head = label;
  if (head.length >= LABEL_WIDTH) {
    head = `${head.slice(0, LABEL_WIDTH - 3)}..`;
  }
  const dots = ".".repeat(LABEL_WIDTH - head.length + 2);
  return `  ${head} ${dots} ${suffix}`;
}

/**
 * Trims a stack trace to its first few frames so failures stay readable.
 *
 * @param {?string} stack - raw stack text
 * @returns {string[]} leading frames (message line plus up to four frames)
 */
function trimStack(stack) {
  if (!stack) {return [];}
  return stack.split("\n").slice(0, 5);
}

/**
 * Formats one failed test as an array of output lines.
 *
 * @param {Object} data - serialized test:fail payload
 * @returns {string[]} lines describing the failure
 */
function failureLines(data) {
  const lines = [`      ✗ ${data.name}`];
  const error = data.details && data.details.error;
  if (!error) {return lines;}
  if (error.message) {lines.push(`        ${error.message}`);}
  for (const frame of trimStack(error.stack)) {
    lines.push(`        ${frame.trim()}`);
  }
  return lines;
}

/** Aggregation state per test file, keyed by display label. */
const groups = new Map();

/**
 * Returns (creating if needed) the aggregation bucket for a test's owning file.
 *
 * @param {Object} data - serialized test event payload
 * @returns {Object} group state
 */
function groupFor(data) {
  const key = labelFor(data.file, data.name || "(unknown)");
  let group = groups.get(key);
  if (!group) {
    group = { passed: 0, failed: 0, skipped: 0, todo: 0, failures: [] };
    groups.set(key, group);
  }
  return group;
}

console.log("i2p+ JS test harness");

const runner = run({
  files,
  isolation: "none"
});

for await (const event of runner) {
  const data = event.data || {};

  if (event.type !== "test:pass" && event.type !== "test:fail") {
    // start/enqueue/plan/diagnostic/stdout/stderr/summary: deliberately silent,
    // keeps build logs free of framework internals and child chatter.
    continue;
  }

  // Suite rollups duplicate their children's results; only leaves count.
  const isSuite = data.details && data.details.type === "suite";
  if (isSuite) {continue;}

  const group = groupFor(data);

  if (event.type === "test:fail") {
    group.failed++;
    group.failures.push(...failureLines(data));
    continue;
  }

  if (data.skip !== undefined && data.skip !== false) {group.skipped++;}
  else if (data.todo !== undefined && data.todo !== false) {group.todo++;}
  else {group.passed++;}
}

let totalPassed = 0;
let totalFailed = 0;
let totalSkipped = 0;
let totalTodo = 0;

for (const [label, group] of groups) {
  const parts = [];
  if (group.failed) {parts.push("FAILED");}
  parts.push(`${group.passed} passed`);
  if (group.skipped) {parts.push(`${group.skipped} skipped`);}
  if (group.todo) {parts.push(`${group.todo} todo`);}
  console.log(leaderLine(label, parts.join(", ")));
  for (const line of group.failures) {console.log(line);}
  totalPassed += group.passed;
  totalFailed += group.failed;
  totalSkipped += group.skipped;
  totalTodo += group.todo;
}

const summary = [`${totalPassed} passed`];
if (totalFailed) {summary.push(`${totalFailed} failed`);}
if (totalSkipped) {summary.push(`${totalSkipped} skipped`);}
if (totalTodo) {summary.push(`${totalTodo} todo`);}
const seconds = ((Date.now() - startedAt) / 1000).toFixed(1);
console.log(`  ${summary.join(", ")} (${seconds}s)`);

process.exitCode = totalFailed > 0 ? 1 : 0;
