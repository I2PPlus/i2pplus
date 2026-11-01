/**
 * Tier-2 pure tests for the bracketed tag words the log linkifier must not
 * turn into netdb links (apps/routerconsole/jsp/js/refreshLogs.js), which are
 * kept in logTags.js so they can be pinned without a browser.
 *
 * The linkifier links any bracketed six characters as a router and any
 * bracketed eight as a lease set, which is the shape of a truncated hash, so a
 * greppable tag in brackets - the leading [Eepget], a state a tally reports -
 * becomes a link that resolves to nothing. These pin that the tags are
 * recognised in the case they are written in, and that a hash prefix of the
 * same length is not.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { isLogTag } from "../../../../apps/routerconsole/jsp/js/logTags.js";

test("the fetch helper's leading tag is a tag in the case it is written in", () => {
  assert.equal(isLogTag("Eepget"), true);
  assert.equal(isLogTag("eepget"), true);
});

test("a bracketed state or field name is a tag", () => {
  for (const word of ["DEFERRED", "accepted", "TunnelId"]) {
    assert.equal(isLogTag(word), true, word);
  }
});

test("a six-character router hash prefix is not a tag", () => {
  for (const hash of ["by7luzwh", "Gk4vTq", "AbC~dE", "N-xomA"]) {
    assert.equal(isLogTag(hash), false, hash);
  }
});

test("an eight-character lease set hash prefix is not a tag", () => {
  for (const hash of ["lxyzdo5p", "79jT5yX8", "frnf1FWE", "AQIDBAUG"]) {
    assert.equal(isLogTag(hash), false, hash);
  }
});

test("an unknown word is not a tag, so a new tag is a deliberate edit", () => {
  assert.equal(isLogTag("zzzzzz"), false);
  assert.equal(isLogTag("SSU"), false);
});
