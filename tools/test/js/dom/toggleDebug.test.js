/**
 * Tier-2 contract tests for the debug-panel toggle in
 * apps/i2psnark/res/js/toggleDebug.js, run against linkedom plus the harness
 * browser shim (no browser needed).
 *
 * These pin the persistence contract the netstatus footer depends on: the stored
 * value is read once at module load and reapplied on every init, the body class and
 * the translated link label agree with that value, the href stays truthful for the
 * no-scripting case, a click flips and persists, and a replaced footer is re-synced
 * rather than left pointing at a detached node.
 *
 * The module caches its config at evaluation time, so each case re-imports it with a
 * cache-busting query after seeding localStorage.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

const MODULE = "../../../../apps/i2psnark/res/js/toggleDebug.js";

/** Footer markup as the servlet renders it, with both labels carried as data attributes. */
const FOOTER = `<!DOCTYPE HTML><html><body>
  <table><tfoot id="snarkFoot"><tr>
    <td><a id="debugMode" href="?p=2" title="Toggle Debug Panel"
           data-debug-label="Debug Mode" data-normal-label="Normal Mode">Debug Mode</a></td>
  </tr></tfoot></table>
</body></html>`;

/**
 * Seeds localStorage, installs the DOM, and loads a fresh copy of the module.
 *
 * @param {?string} stored value to pre-seed under snarkDebugMode, or null for no entry
 * @returns {Promise<{toggleDebug: Function}>} the module's exported initializer
 */
async function load(stored) {
  installBrowserShim();
  if (stored === null) { localStorage.removeItem("snarkDebugMode"); }
  else { localStorage.setItem("snarkDebugMode", stored); }
  document.documentElement.innerHTML = parseHTML(FOOTER).document.body.innerHTML;
  document.body.classList.remove("debug", "debugListener");
  const mod = await import(`${MODULE}?case=${Math.random()}`);
  return {toggleDebug: mod.toggleDebug};
}

/**
 * Dispatches a real bubbling click on the toggle link, so the module's delegated
 * document-level listener runs the same path the browser takes.
 *
 * linkedom's Event is used explicitly: Node's native Event is a read-only eventPhase
 * wrapper and throws when linkedom tries to run its dispatch algorithm on it.
 *
 * @returns {void}
 */
function click() {
  const link = document.getElementById("debugMode");
  link.dispatchEvent(new window.Event("click", {bubbles: true, cancelable: true}));
}

test("defaults to the panel hidden and a link that turns it on", async () => {
  const {toggleDebug} = await load(null);
  toggleDebug();
  assert.equal(document.body.classList.contains("debug"), false);
  assert.equal(document.getElementById("debugMode").textContent, "Debug Mode");
  assert.equal(document.getElementById("debugMode").getAttribute("href"), "?p=2");
});

test("restores a stored 'on' state and shows the translated off-label", async () => {
  const {toggleDebug} = await load("on");
  toggleDebug();
  assert.equal(document.body.classList.contains("debug"), true);
  // Label comes from the server's data attributes, never hardcoded in the module.
  assert.equal(document.getElementById("debugMode").textContent, "Normal Mode");
  assert.equal(document.getElementById("debugMode").getAttribute("href"), "?p");
});

test("a click flips the class and persists the new state", async () => {
  const {toggleDebug} = await load(null);
  toggleDebug();
  click();
  assert.equal(document.body.classList.contains("debug"), true);
  assert.equal(localStorage.getItem("snarkDebugMode"), "on");
  click();
  assert.equal(document.body.classList.contains("debug"), false);
  assert.equal(localStorage.getItem("snarkDebugMode"), "off");
});

test("re-init after an AJAX refresh re-syncs replaced footer markup", async () => {
  const {toggleDebug} = await load("on");
  toggleDebug();
  // The refresh path replaces the footer wholesale, handing back a detached link.
  document.body.innerHTML = parseHTML(FOOTER).document.body.innerHTML;
  const fresh = document.getElementById("debugMode");
  assert.equal(fresh.textContent, "Debug Mode", "new markup starts from the server default");
  toggleDebug();
  assert.equal(fresh.textContent, "Normal Mode", "re-init must re-apply the stored state");
  assert.equal(fresh.getAttribute("href"), "?p");
});

test("repeated init does not stack duplicate click listeners", async () => {
  const {toggleDebug} = await load(null);
  toggleDebug();
  toggleDebug();
  toggleDebug();
  click();
  assert.equal(localStorage.getItem("snarkDebugMode"), "on", "three inits must register one listener");
});
