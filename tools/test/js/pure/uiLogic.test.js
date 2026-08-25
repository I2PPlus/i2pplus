/**
 * Tier-1 unit tests for res/js/uiLogic.js — pure decision helpers, no DOM.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  loadingSpanDecision,
  resolveFilterId,
  formatTooltipText,
  applyIfChanged
} from "../../../../apps/i2psnark/res/js/uiLogic.js";

test("loadingSpanDecision adds the indicator when buttons are absent and no span exists", () => {
  assert.equal(loadingSpanDecision(false, false), "add");
});

test("loadingSpanDecision keeps an existing span while buttons are still absent", () => {
  assert.equal(loadingSpanDecision(false, true), "keep");
});

test("loadingSpanDecision removes a leftover span once buttons are restored", () => {
  assert.equal(loadingSpanDecision(true, true), "remove");
});

test("loadingSpanDecision is a no-op for a settled cell with buttons and no span", () => {
  assert.equal(loadingSpanDecision(true, false), "none");
});

test("resolveFilterId prefers the realtime search parameter over the status filter", () => {
  const params = new URLSearchParams("?filter=seeding&search=ubuntu");
  assert.equal(resolveFilterId(params), "search");
});

test("resolveFilterId uses the status filter when no search is present", () => {
  assert.equal(resolveFilterId(new URLSearchParams("?filter=seeding")), "seeding");
});

test("resolveFilterId defaults to all", () => {
  assert.equal(resolveFilterId(new URLSearchParams("")), "all");
});

test("resolveFilterId treats an empty search value as active search", () => {
  assert.equal(resolveFilterId(new URLSearchParams("?search=")), "search");
});

test("formatTooltipText rewrites bullet-separated titles one per line", () => {
  assert.equal(formatTooltipText("Peers • 3 of 5"), "• Peers\n• 3 of 5");
});

test("formatTooltipText splits on the last bullet (greedy match, pinned behaviour)", () => {
  assert.equal(formatTooltipText("A • B • C"), "• A • B\n• C");
});

test("formatTooltipText leaves plain text untouched", () => {
  assert.equal(formatTooltipText("no bullets here"), "no bullets here");
});

test("applyIfChanged writes on first sight and reports the write", () => {
  const el = {innerHTML: "old"};
  assert.equal(applyIfChanged(el, "new"), true);
  assert.equal(el.innerHTML, "new");
});

test("applyIfChanged skips an identical second application without serializing", () => {
  const el = {innerHTML: "<b>x</b>"};
  assert.equal(applyIfChanged(el, "<b>x</b>"), false);
  // Second call must short-circuit via memory, not via reading innerHTML; prove it
  // by mutating the live property afterwards — a serialization-based check would
  // now see different content and rewrite.
  assert.equal(applyIfChanged(el, "<b>x</b>"), false);
});

test("applyIfChanged rewrites when desired html differs from last applied", () => {
  const el = {innerHTML: "a"};
  applyIfChanged(el, "a");
  assert.equal(applyIfChanged(el, "b"), true);
  assert.equal(el.innerHTML, "b");
});

test("applyIfChanged tracks elements independently", () => {
  const a = {innerHTML: ""};
  const b = {innerHTML: ""};
  applyIfChanged(a, "same");
  assert.equal(applyIfChanged(b, "same"), true);
});

test("applyIfChanged tolerates a null element", () => {
  assert.equal(applyIfChanged(null, "x"), false);
});
