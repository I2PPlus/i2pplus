/**
 * Tier-1 unit tests for extractNonce() in apps/i2psnark/res/js/refreshPayload.js —
 * the CSRF nonce parser used by the refresh pipeline to keep the torrent form's
 * hidden nonce in sync without re-rendering it.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { extractNonce } from "../../../../apps/i2psnark/res/js/refreshPayload.js";

test("extractNonce pulls the hidden input value from rendered form HTML", () => {
  const html = '<form id=torrentlist action=_post method=POST><input type=hidden name=nonce value="12345">';
  assert.equal(extractNonce(html), "12345");
});

test("extractNonce tolerates whitespace between the attributes", () => {
  const html = '<input type=hidden name=nonce  value="6789">';
  assert.equal(extractNonce(html), "6789");
});

test("extractNonce returns an empty string for an empty value attribute", () => {
  const html = '<input name=nonce value="">';
  assert.equal(extractNonce(html), "");
});

test("extractNonce returns null when no nonce is present", () => {
  assert.equal(extractNonce("<form><input type=text></form>"), null);
});

test("extractNonce returns null for null or empty payloads", () => {
  assert.equal(extractNonce(null), null);
  assert.equal(extractNonce(undefined), null);
  assert.equal(extractNonce(""), null);
});
