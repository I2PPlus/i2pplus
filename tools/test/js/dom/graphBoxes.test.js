/**
 * Contract tests for apps/routerconsole/jsp/js/graphBoxes.js - the one place the console
 * decides how big a graph's box on the page is.
 *
 * The graphs page pins every .graphContainer to one size so that two dozen graphs line up
 * as a grid rather than as a ragged set of tiles. That size has to come from the pictures
 * the router drew, not from the plot sizes their srcs asked for: a tile plotting send and
 * receive together spends more room on two legends than the plain stats beside it at the
 * same plot size, and a box pinned to the smaller picture clips the taller one.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { installBrowserShim } from "../helpers/browserShim.js";

/** The module under test. */
const MODULE = "../../../../apps/routerconsole/jsp/js/graphBoxes.js";

/**
 * Loads the module fresh, so nothing carries between tests.
 *
 * @returns {Promise<Object>} the module namespace
 */
async function load() {
  installBrowserShim();
  return import(`${MODULE}?t=${Math.random()}`);
}

/**
 * A graph whose picture has come back at the given rendered size.
 *
 * @param {number} width the picture's width in px
 * @param {number} height the picture's height in px
 * @returns {Object} an image the module can measure
 */
const drawn = (width, height) => ({naturalWidth: width, naturalHeight: height});

/**
 * A style element that counts how many times its rule is written.
 *
 * @returns {Object} the stand-in, with its write count
 */
const countedStyle = () => ({
  text: "",
  writes: 0,
  get textContent() {return this.text;},
  set textContent(value) {this.text = value; this.writes++;}
});

/**
 * A graph image carrying a size of its own, as an attribute.
 *
 * @param {Object} [attrs] the attributes to start with; both sizes if not given
 * @returns {Object} an image the module can read and change, with the attributes it holds
 */
const sized = (attrs) => {
  const held = attrs || {width: "379", height: "212"};
  return {
    attrs: held,
    getAttribute: (name) => (name in held ? held[name] : null),
    removeAttribute: (name) => {delete held[name];}
  };
};

test("a graph has no box until its picture has come back", async () => {
  const m = await load();
  assert.equal(m.graphBox(drawn(0, 0)), null, "never asked for");
  assert.equal(m.graphBox({naturalWidth: 696, naturalHeight: 0}), null, "asked for, not back yet");
  assert.deepEqual(m.graphBox(drawn(696, 133)), {width: 696, height: 133});
});

test("the box is the biggest picture on the page, not the first one", async () => {
  const m = await load();
  // A plain stat and the combined bandwidth tile, both asked for the same 613x82 plot: the
  // tile's second legend makes its picture 38px taller.
  const plain = drawn(696, 217);
  const combined = drawn(696, 255);
  assert.deepEqual(m.tallestBox([plain, combined]), {width: 696, height: 255}, "the taller of the two");
  assert.deepEqual(m.tallestBox([combined, plain]), {width: 696, height: 255}, "document order does not matter");
  // Width and height are taken independently: a wide short graph and a narrow tall one
  // together need a box that is both, or one of them is clipped.
  assert.deepEqual(m.tallestBox([drawn(1200, 100), drawn(400, 500)]), {width: 1200, height: 500});
  assert.equal(m.tallestBox([]), null, "no graphs, nothing to measure");
  assert.equal(m.tallestBox([drawn(0, 0)]), null, "none has come back yet");
});

test("the pin is the page's own rule, plus the box's border and padding", async () => {
  const m = await load();
  const style = countedStyle();
  assert.equal(m.pinGraphBoxes(style, null), false, "nothing measured yet");
  assert.equal(style.textContent, "", "and the rule is left alone");
  assert.equal(m.pinGraphBoxes(style, {width: 696, height: 255}), true);
  assert.equal(style.textContent, ".graphContainer { width: 700px; height: 259px; }");
  assert.equal(m.pinGraphBoxes(style, {width: 696, height: 255}), true);
  assert.equal(style.writes, 1, "the same size does not write the rule again");
  m.pinGraphBoxes(style, {width: 696, height: 300});
  assert.equal(style.writes, 2, "a different size does");
  assert.equal(m.pinGraphBoxes(null, {width: 696, height: 300}), false, "no rule to write to");
});

test("a resized graph is drawn at its own px, not scaled into the box it started at", async () => {
  const m = await load();
  // The page the console built carries the size it drew at on the image itself, and a
  // browser scales every later picture into that box: the container grows, the graph inside
  // it does not, and the proportions go with it.
  const img = sized();
  assert.equal(m.freeGraphSize(img), true);
  assert.deepEqual(img.attrs, {}, "the size the page was built at is off it");
  assert.equal(m.freeGraphSize(img), false, "and taking it off twice changes nothing");
  // Only one axis pinned is still a box the picture is scaled into.
  assert.equal(m.freeGraphSize(sized({height: "212"})), true);
  // A graph that never carried a size of its own is left alone.
  assert.equal(m.freeGraphSize(sized({})), false);
});
