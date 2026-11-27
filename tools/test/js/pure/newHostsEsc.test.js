/**
 * Tier-2 pure tests for the tooltip escaper in apps/routerconsole/jsp/js/newHosts.js.
 *
 * The new-hosts tooltip lists hostnames read out of the DNS log, so the text it
 * shows is not ours, and it is written into markup and into an href. The escaper
 * claims the result is safe inside a quoted attribute, which is only true if the
 * apostrophe is escaped as well: a single-quoted attribute is closed by a bare
 * quote. These pin each metacharacter to its entity, pin the ordering that keeps
 * an ampersand in the input from being re-escaped by the later substitutions, and
 * pin the shape the call site actually feeds it.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { esc } from "../../../../apps/routerconsole/jsp/js/newHosts.js";

test("an angle bracket cannot restructure the tooltip", () => {
  assert.equal(esc("<img src=x>"), "&lt;img src=x&gt;");
});

test("a quote closes neither a double- nor a single-quoted attribute", () => {
  assert.equal(esc(`a"b`), "a&quot;b");
  assert.equal(esc("a'b"), "a&#39;b");
});

test("the ampersand is escaped first, so the later entities are not re-escaped", () => {
  assert.equal(esc("&amp;"), "&amp;amp;");
  assert.equal(esc("<'"), "&lt;&#39;");
});

test("a hostname from the DNS log round-trips unchanged", () => {
  assert.equal(esc("example.i2p"), "example.i2p");
  assert.equal(esc("a-b_c.example.i2p"), "a-b_c.example.i2p");
});

test("a non-string value is coerced rather than thrown at", () => {
  assert.equal(esc(42), "42");
  assert.equal(esc(null), "null");
});
