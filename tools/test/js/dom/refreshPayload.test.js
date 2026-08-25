/**
 * Tier-2 contract tests for extractRefreshPayload() in
 * apps/i2psnark/res/js/refreshPayload.js, run against a snapshot of the real
 * /.ajax/xhr1.html document structure via linkedom (no browser needed).
 *
 * These pin the payload contract the refresh pipeline depends on: field presence,
 * null-safety when optional containers are missing, and nonce extraction.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseHTML } from "linkedom";
import { extractRefreshPayload } from "../../../../apps/i2psnark/res/js/refreshPayload.js";

const fixture = () =>
  parseHTML(
    readFileSync(fileURLToPath(new URL("../fixtures/torrents-list.html", import.meta.url)), "utf8")
  ).document;

test("extractRefreshPayload pulls the torrent tbody HTML", () => {
  const payload = extractRefreshPayload(fixture());
  assert.ok(payload.snarkTbody.includes('data-name="ubuntu-24.04.iso"'));
  assert.ok(payload.snarkTbody.includes('data-name="debian-12.iso"'));
});

test("extractRefreshPayload extracts header and footer th cells in order", () => {
  const payload = extractRefreshPayload(fixture());
  assert.deepEqual(payload.headerTH, ["Name", "Status", "Progress"]);
  assert.equal(payload.footerTH[0], "Totals");
});

test("extractRefreshPayload reads the active all-filter badge text", () => {
  const payload = extractRefreshPayload(fixture());
  assert.equal(payload.badgeText, "7");
  assert.equal(payload.filterBarPresent, true);
});

test("extractRefreshPayload captures pagination markup", () => {
  const payload = extractRefreshPayload(fixture());
  assert.ok(payload.pagenavtop.includes("Page"));
  assert.ok(payload.mainsection.includes('id="torrentlist"') || payload.mainsection.includes("id=torrentlist"));
});

test("extractRefreshPayload carries the CSRF nonce from the form", () => {
  const payload = extractRefreshPayload(fixture());
  assert.equal(payload.nonce, "1712345678");
});

test("extractRefreshPayload returns a null nonce when no form is present", () => {
  const { document } = parseHTML(
    '<!DOCTYPE HTML><html><body id=snarkxhr><div id=mainsection></div></body></html>'
  );
  const payload = extractRefreshPayload(document);
  assert.equal(payload.nonce, null);
});

test("extractRefreshPayload lists dht debug row outerHTML", () => {
  const payload = extractRefreshPayload(fixture());
  assert.equal(payload.dhtDebug.length, 1);
  assert.ok(payload.dhtDebug[0].includes("peers: 5"));
});

test("extractRefreshPayload returns null messages when none are rendered", () => {
  const payload = extractRefreshPayload(fixture());
  assert.equal(payload.messages, null);
});

test("extractRefreshPayload carries the screen log stamp for change gating", () => {
  const payload = extractRefreshPayload(fixture());
  assert.equal(payload.screenLogStamp, "4213:7");
});

test("extractRefreshPayload returns a null screen log stamp when absent", () => {
  const { document } = parseHTML(
    '<!DOCTYPE HTML><html><body id=snarkxhr><div id=mainsection></div></body></html>'
  );
  const payload = extractRefreshPayload(document);
  assert.equal(payload.screenLogStamp, null);
});

test("extractRefreshPayload tolerates a response with no filter bar", () => {
  const { document } = parseHTML(
    '<!DOCTYPE HTML><html><body id=snarkxhr><div id=mainsection><table><thead id=snarkHead><tr><th>A</th></tr></thead><tbody id=snarkTbody></tbody></table></div></body></html>'
  );
  const payload = extractRefreshPayload(document);
  assert.equal(payload.badgeText, null);
  assert.equal(payload.filterBarPresent, false);
  assert.equal(payload.snarkTbody, "");
  assert.deepEqual(payload.headerTH, ["A"]);
});
