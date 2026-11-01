/**
 * Tier-2 contract tests for the log linkifier in
 * apps/routerconsole/jsp/js/refreshLogs.js, run against linkedom plus the
 * harness browser shim (no browser needed).
 *
 * These pin what the reader sees on /logs: a bracketed six-character router
 * hash and a bracketed eight-character lease set hash become netdb links, a
 * floodfill hash is linked with its floodfill class, and a bracketed tag word -
 * the leading [Eepget], a state a tally reports - stays plain text, because
 * the same bracket shape is what marks a hash and the tag is not one.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

const MODULE = "../../../../apps/routerconsole/jsp/js/refreshLogs.js";

/** One log line carrying every bracket form the linkifier is asked about. */
const ENTRY = `<!DOCTYPE HTML><html><body><ul id="logs"><li>
  [Eepget] Fetched the blocklist -&gt; Router [Dj66qA] * Lease [79jT5yX8]
  * State: [DEFERRED] * floodfill [AbC~dE]
</li></ul></body></html>`;

/**
 * Installs the shim, loads a fresh copy of the module, and linkifies the list.
 *
 * @returns {Promise<string>} the list's markup after linkification
 */
async function linkified() {
  installBrowserShim();
  document.documentElement.innerHTML = parseHTML(ENTRY).document.body.innerHTML;
  const { linkifyLogEntries } = await import(`${MODULE}?case=${Math.random()}`);
  linkifyLogEntries(document.getElementById("logs"));
  return document.getElementById("logs").innerHTML;
}

test("a bracketed six-character router hash becomes a netdb router link", async () => {
  const html = await linkified();
  assert.ok(html.includes('href="/netdb?r=Dj66qA"'), html);
});

test("a bracketed eight-character lease set hash becomes a netdb lease link", async () => {
  const html = await linkified();
  assert.ok(html.includes('href="/netdb?l=3#79jT"'), html);
});

test("a floodfill hash is linked and carries the floodfill class", async () => {
  const html = await linkified();
  assert.ok(html.includes('href="/netdb?r=AbC~dE"'), html);
  assert.ok(html.includes('class="isFF"'), html);
});

test("a bracketed tag word stays plain text, in either length", async () => {
  const html = await linkified();
  assert.ok(html.includes("[Eepget]"), html);
  assert.ok(html.includes("[DEFERRED]"), html);
  assert.ok(!html.includes("Eepget</a>"), html);
  assert.ok(!html.includes("DEFERRED</a>"), html);
});
