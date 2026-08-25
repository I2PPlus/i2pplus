/**
 * Contract test: every i2psnark client script that uses ES module syntax must be
 * loaded with type=module. Regression guard for the convertTooltips.js incident,
 * where a file gained a top-level import while its servlet-emitted <script> tag
 * stayed classic, producing "import declarations may only appear at top level of
 * a module" at runtime.
 *
 * Strategy: classify every file in apps/i2psnark/res/js as ESM (top-level
 * import/export) or classic, then scan the known emitters of script tags — the
 * servlet source and the standalone index page — for references to those files.
 * Any <script> tag referencing an ESM file must carry type=module. Occurrences
 * that are not script tags (inline import statements, build strings) are ignored
 * via a proximity window around each reference.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
// dom -> js -> test -> tools -> repo root
const repoRoot = join(here, "..", "..", "..", "..");

const JS_DIR = join(repoRoot, "apps", "i2psnark", "res", "js");
const EMITTER_SOURCES = [
  join(repoRoot, "apps", "i2psnark", "java", "src", "org", "klomp", "snark", "web", "I2PSnarkServlet.java"),
  join(repoRoot, "apps", "i2psnark", "standalone-index.html")
];

/**
 * Classifies a client script as ESM based on top-level import/export statements
 * at line starts, which JSDoc comment blocks (lines beginning with " *") cannot
 * produce.
 *
 * @param {string} source - File contents.
 * @returns {boolean} Whether the file requires module loading semantics.
 */
function isEsm(source) {
  return /^(?:import|export)\s/m.test(source);
}

/**
 * Returns the full <script ...> tag surrounding a reference position, or null when
 * the reference is too far from any tag opening to plausibly be its src attribute
 * (e.g. an inline `import ... from ".../x.js"` statement).
 *
 * @param {string} text - Emitter source being scanned.
 * @param {number} idx - Index of the filename reference within text.
 * @returns {?string} The enclosing tag, including angle brackets.
 */
function tagAround(text, idx) {
  const tagStart = text.lastIndexOf("<script", idx);
  if (tagStart === -1) {return null;}
  const tagEnd = text.indexOf(">", idx);
  if (tagEnd === -1 || tagEnd - tagStart > 400) {return null;}
  return text.slice(tagStart, tagEnd + 1);
}

test("esm client scripts are always loaded with type=module", () => {
  const esmFiles = readdirSync(JS_DIR)
    .filter((name) => name.endsWith(".js"))
    .filter((name) => isEsm(readFileSync(join(JS_DIR, name), "utf8")));
  assert.ok(esmFiles.includes("convertTooltips.js"), "sanity: convertTooltips.js classified as ESM");

  const violations = [];

  for (const emitter of EMITTER_SOURCES) {
    const text = readFileSync(emitter, "utf8");
    for (const name of esmFiles) {
      const needle = `${name}.js`;
      let idx = text.indexOf(needle);
      while (idx !== -1) {
        const tag = tagAround(text, idx);
        if (tag && !/\btype\s*=\s*"?module\b/.test(tag)) {
          violations.push(`${name} referenced without type=module in ${emitter.split("/").pop()}: ${tag}`);
        }
        idx = text.indexOf(needle, idx + needle.length);
      }
    }
  }

  assert.deepEqual(violations, []);
});

test("classic scripts stay classic: no false positives among non-esm files", () => {
  // Inverse sanity check: well-known classic (no-import) scripts must exist and be
  // classified as such, pinning that isEsm() is not flagging everything.
  const classic = ["graphRefresh.js", "togglePriorities.js", "textView.js"];
  for (const name of classic) {
    const source = readFileSync(join(JS_DIR, name), "utf8");
    assert.equal(isEsm(source), false, `${name} unexpectedly classified as ESM`);
  }
});
