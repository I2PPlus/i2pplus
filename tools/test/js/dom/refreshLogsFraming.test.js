/**
 * Tier-2 contract tests for the column-framing passes in
 * apps/routerconsole/jsp/js/refreshLogs.js, run against linkedom plus the
 * harness browser shim (no browser needed).
 *
 * These pin what the reader sees at the head of every line on /routerlogs: the
 * pipe the file log uses to delimit its columns is dropped between the date and
 * the level badge, and the ASCII truncation marker the formatter writes in the
 * two fixed-width columns shows as a real ellipsis without costing the column a
 * cell. The things that make "replace the dots wherever" wrong are all here - a
 * hex dump full of pipes, a ".." in a path, and a configured format that puts no
 * framed columns on the line at all.
 *
 * The fixtures are the console's own output, entities resolved to the characters
 * a text node actually holds: the server writes a padded column's spaces as
 * &nbsp; so HTML cannot collapse them, and its rewrite of three consecutive dots
 * has already folded a marker whose tail began with a dot. The formatter pads
 * both columns to a fixed width, truncated or not, so every field here is
 * exactly that wide before the pass runs.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

const MODULE = "../../../../apps/routerconsole/jsp/js/refreshLogs.js";

const ELL = "\u2026";
const NBSP = "\u00a0";

/**
 * Log entries as the console writes them: the date, the column separator, the
 * level badge the server wraps the level in, the two fixed-width columns, then a
 * message. The thread column is twelve wide and the source column sixteen,
 * padded with &nbsp;; the first line's columns are both truncated, the second's
 * source column is truncated where the tail began with a dot and the server's
 * dot rewrite has folded it, and the third's thread column is a legacy line
 * whose marker is already an ellipsis.
 */
const ENTRY = `<!DOCTYPE HTML><html><body><ul id="logs">
<li class="log_warn colorize">2026-11-04 00:22:14.809 | <span class=log_level>WARN</span> [..j:er:Dj66.] ..st.Net.Connect: Fetch failed \u2192 timeout
<br>&nbsp;&nbsp;00000000  68 65 6c 6c 6f  |hello|</li>
<li class="log_info colorize">2026-11-04 00:22:14.809 | <span class=log_level>INFO</span> [NTCPRead.2${NBSP}${NBSP}] &hellip;st.Net${NBSP}Connect: opened /home/../../etc/passwd</li>
<li class="log_info colorize">2026-11-04 00:22:14.809 | <span class=log_level>INFO</span> [${ELL}ueue${NBSP}Pumper] net.i2p.router.Fo: a legacy marker</li>
<li class="log_omitted">2026-11-04 00:22:14.809 | &darr;&darr;&darr; 12 messages omitted</li>
</ul></body></html>`;

/**
 * Installs the shim, loads a fresh copy of the module and runs both passes over
 * the list.
 *
 * @param {number} [passes] how many times to run them, to prove they are idempotent
 * @returns {Promise<string>} the list's text afterwards
 */
async function rendered(passes = 1) {
  installBrowserShim();
  document.documentElement.innerHTML = parseHTML(ENTRY).document.body.innerHTML;
  const { stripLevelSeparator, renderColumnEllipsis } = await import(`${MODULE}?case=${Math.random()}`);
  const logs = document.getElementById("logs");
  for (let i = 0; i < passes; i++) {
    stripLevelSeparator(logs);
    renderColumnEllipsis(logs);
  }
  return logs.textContent;
}

test("the separator between the date and the level badge is gone", async () => {
  const text = await rendered();
  assert.ok(text.includes(`WARN [${ELL}j:er:Dj66.`), text);
  assert.ok(!text.includes("00:22:14.809 | WARN"), text);
  assert.ok(!text.includes("00:22:14.809 | INFO"), text);
});

test("a line with no level badge keeps its separator", async () => {
  const text = await rendered();
  assert.ok(text.includes("00:22:14.809 | \u2193\u2193\u2193"), text);
});

test("the hex dump's own pipes are left alone", async () => {
  const text = await rendered();
  assert.ok(text.includes("|hello|"), text);
});

test("a truncated thread column shows an ellipsis and keeps its twelve cells", async () => {
  const text = await rendered();
  assert.ok(text.includes(`[${ELL}j:er:Dj66.${NBSP}]`), text);
});

test("a truncated source column shows an ellipsis and keeps its sixteen cells", async () => {
  const text = await rendered();
  assert.ok(text.includes(`${ELL}st.Net.Connect${NBSP}:`), text);
  // the tail began with a dot and the server's dot rewrite folded a second cell
  assert.ok(text.includes(`NTCPRead.2${NBSP}${NBSP}] ${ELL}st.Net${NBSP}Connect${NBSP}:`), text);
});

test("a legacy marker is left at the width it is already at", async () => {
  const text = await rendered();
  assert.ok(text.includes(`[${ELL}ueue${NBSP}Pumper]`), text);
});

test("dots in the message body are left alone", async () => {
  const text = await rendered();
  assert.ok(text.includes("/home/../../etc/passwd"), text);
});

test("a configured format with no framed columns leaves the line alone", async () => {
  installBrowserShim();
  const entry = `<!DOCTYPE HTML><html><body><ul id="logs"><li>2026-11-04 00:22:14.809 | <span class=log_level>INFO</span> [Eepget] fetched /a/../b for 1234567890123456: done</li></ul></body></html>`;
  document.documentElement.innerHTML = parseHTML(entry).document.body.innerHTML;
  const { renderColumnEllipsis } = await import(`${MODULE}?case=${Math.random()}`);
  renderColumnEllipsis(document.getElementById("logs"));
  assert.ok(document.getElementById("logs").textContent.includes("[Eepget] fetched /a/../b"),
            document.getElementById("logs").textContent);
});

test("running the passes twice changes nothing further", async () => {
  assert.equal(await rendered(2), await rendered(1));
});

test("a container that is not there is not an error", async () => {
  installBrowserShim();
  const { stripLevelSeparator, renderColumnEllipsis } = await import(`${MODULE}?case=${Math.random()}`);
  stripLevelSeparator(null);
  renderColumnEllipsis(null);
});
