/**
 * Tier-2 pure tests for the harness's own reachability: every test file it
 * holds has to be imported by suite.test.js, or it never runs and nothing says
 * so.
 *
 * helpers/run.mjs runs one entry point - suite.test.js - and prints a line per
 * file that produced a result. A test file nobody imports produces no line at
 * all, so the totals line still reads as a pass with that file's coverage
 * simply absent: the count is lower by however many tests it held, and no
 * output names the file that was skipped. These pin the import list against
 * the directory listing in both directions - a file with no import, and an
 * import with no file - so a new suite cannot land unwired and a renamed one
 * cannot leave a stale line behind.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";

/** The harness directory - this file sits one level below it. */
const HARNESS = new URL("../", import.meta.url);
/** The single entry point run.mjs invokes. */
const SUITE = new URL("suite.test.js", HARNESS);
/** Directories the harness keeps test files in. */
const TEST_DIRS = ["pure", "dom"];

/**
 * Every test file the harness holds, as the import specifier that would reach
 * it ("./dom/refreshLogs.test.js").
 *
 * @returns {Promise<string[]>} sorted specifiers
 */
async function listedTests() {
  const out = [];
  for (const dir of TEST_DIRS) {
    for (const entry of await readdir(new URL(`./${dir}/`, HARNESS), { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith(".test.js")) {out.push(`./${dir}/${entry.name}`);}
    }
  }
  return out.sort();
}

/**
 * Every test file suite.test.js actually pulls in, static or dynamic.
 *
 * @returns {Promise<string[]>} specifiers in file order, duplicates kept
 */
async function importedTests() {
  const src = await readFile(SUITE, "utf8");
  const found = [];
  // `import "./x.test.js";` and `await import("./x.test.js");` both register a
  // suite; the smoke gate is dynamic because it self-skips without linkedom.
  for (const m of src.matchAll(/import\s*(?:\(\s*)?["'](\.\/[^"']+\.test\.js)["']/g)) {
    found.push(m[1]);
  }
  return found;
}

test("every test file in the harness is imported by the suite", async () => {
  const imported = new Set(await importedTests());
  const unwired = (await listedTests()).filter((f) => !imported.has(f));
  assert.deepEqual(unwired, [],
                   `${unwired.length} test file(s) never run: ${unwired.join(", ")}`);
});

test("the suite imports no test file that is not there", async () => {
  const listed = new Set(await listedTests());
  const stale = (await importedTests()).filter((f) => !listed.has(f));
  assert.deepEqual(stale, [],
                   `${stale.length} import(s) name no test file: ${stale.join(", ")}`);
});

test("no test file is imported twice", async () => {
  const imported = await importedTests();
  const dupes = imported.filter((f, i) => imported.indexOf(f) !== i);
  assert.deepEqual(dupes, [], `${dupes.length} test file(s) imported twice: ${dupes.join(", ")}`);
});
