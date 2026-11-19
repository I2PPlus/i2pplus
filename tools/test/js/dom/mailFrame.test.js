/**
 * Tier-2 contract tests for the SusiMail HTML message frame handler in
 * apps/susimail/src/js/mailFrame.js, run against linkedom (no browser needed).
 *
 * These pin the arrangement that replaced an in-frame script: the frame is
 * sandboxed and runs nothing, so every thing a message needed - its `!important`
 * declarations taken out, its blocked remote images given a placeholder, the tallies
 * of what was blocked or removed reported in the page, the frame sized to its
 * content - is done from the page against the frame's document. The tests drive the
 * exported helpers directly, because the whole point of the arrangement is that the
 * work is reachable from outside the frame.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { parseHTML } from "linkedom";
import {
  BLOCKED_ICON,
  FRAME_ID,
  WEB_BUG_ICON,
  blockedImages,
  createToggle,
  decorateFrame,
  frameDocument,
  frameHeight,
  init,
  markBlockedImages,
  normalizeStyle,
  normalizeStyles,
  setHidden,
  showCounts,
  webBugs
} from "../../../../apps/susimail/src/js/mailFrame.js";

/** A message document as the server hands it over: defanged images, bug placeholders. */
const MESSAGE = `<!DOCTYPE HTML><html><head></head><body>
  <div style="color:red !important">a</div>
  <p style="height: 100%; color:blue">b</p>
  <img data-src-blocked="http://evil.example/px.gif" src="http://evil.example/px.gif">
  <img data-src-blocked="//evil.example/other.png" src="//evil.example/other.png">
  <span class="webBug" hidden></span>
  <span id="endOfPage" data-iframe-height></span>
</body></html>`;

/** The page holding the frame, with the info bar the tallies are reported into. */
const PAGE = `<!DOCTYPE HTML><html><head></head><body>
  <tr id="blockedImages" hidden><td colspan="2"><p class="info">
    <span id="webBugs" hidden>removed <span id="webBugCount"></span><br></span>
    <span id="blockedImgLine" hidden>blocked <span id="blockedImgCount"></span></span>
  </p></td></tr>
  <iframe id="${FRAME_ID}" sandbox="allow-same-origin"></iframe>
</body></html>`;

/**
 * Parses one of the two fixtures.
 *
 * @param {string} html the document as it is served
 * @returns {Document} the parsed document
 */
function doc(html) {
  return parseHTML(html).document;
}

test("a sender's !important is taken out and the rest of the style is kept", () => {
  assert.equal(normalizeStyle("color:red !important"), "color:red");
  assert.equal(normalizeStyle("color: RED !IMPORTANT; margin:0px"), "color: RED ; margin:0px");
});

test("a height of 100% is taken out, because the frame is sized to its content", () => {
  assert.equal(normalizeStyle("height: 100%; color:blue"), "color:blue");
  assert.equal(normalizeStyle("HEIGHT:100%"), "");
  assert.equal(normalizeStyle("max-height: 100%"), "max-height: 100%");
});

test("an ordinary style is left exactly as the sender wrote it", () => {
  assert.equal(normalizeStyle("color:blue"), "color:blue");
  assert.equal(normalizeStyle(""), "");
  assert.equal(normalizeStyle(null), "");
});

test("the styles of a whole message are rewritten in place", () => {
  const d = doc(MESSAGE);
  normalizeStyles(d);
  assert.equal(d.querySelector("div").getAttribute("style"), "color:red");
  assert.equal(d.querySelector("p").getAttribute("style"), "color:blue");
});

test("the blocked images and the tracking placeholders are counted separately", () => {
  const d = doc(MESSAGE);
  assert.equal(blockedImages(d).length, 2);
  assert.equal(webBugs(d).length, 1);
  const empty = doc("<!DOCTYPE HTML><html><body></body></html>");
  assert.deepEqual(blockedImages(empty), []);
  assert.deepEqual(webBugs(empty), []);
});

test("a blocked image gets the placeholder icon and keeps its URL as the title", () => {
  const d = doc(MESSAGE);
  markBlockedImages(d);
  const first = d.querySelectorAll("img")[0];
  assert.equal(first.getAttribute("src"), BLOCKED_ICON);
  assert.equal(first.getAttribute("title"), "http://evil.example/px.gif");
  assert.equal(d.querySelectorAll("img")[1].getAttribute("title"), "//evil.example/other.png");
});

test("hiding and showing the blocked elements is reversible", () => {
  const d = doc(MESSAGE);
  const images = blockedImages(d);
  setHidden(images, true);
  images.forEach(image => {
    assert.equal(image.getAttribute("hidden"), "hidden");
    assert.equal(image.style.display, "none");
  });
  setHidden(images, false);
  images.forEach(image => {
    assert.equal(image.getAttribute("hidden"), null);
    assert.equal(image.style.display, "inline-block");
  });
});

test("the page reports both tallies and unhides the bar", () => {
  const page = doc(PAGE);
  const message = doc(MESSAGE);
  showCounts(page, blockedImages(message), webBugs(message));
  const bar = page.getElementById("blockedImages");
  assert.equal(bar.hasAttribute("hidden"), false);
  assert.equal(page.getElementById("blockedImgLine").hasAttribute("hidden"), false);
  assert.equal(page.getElementById("webBugs").hasAttribute("hidden"), false);
  assert.equal(page.getElementById("blockedImgCount").textContent, "2");
  assert.equal(page.getElementById("webBugCount").textContent, "1");
  assert.ok(page.querySelector("#blockedImages .info").classList.contains("hasWebBugs"));
  // the break between the two tallies becomes the house separator
  assert.equal(page.querySelector("#webBugs br"), null);
  assert.ok(page.getElementById("webBugs").textContent.includes("•"));
});

test("a message with only tracking images still reports them", () => {
  const page = doc(PAGE);
  const message = doc("<!DOCTYPE HTML><html><body><span class=\"webBug\"></span></body></html>");
  showCounts(page, blockedImages(message), webBugs(message));
  assert.equal(page.getElementById("blockedImages").hasAttribute("hidden"), false);
  assert.equal(page.getElementById("webBugs").hasAttribute("hidden"), false);
  assert.equal(page.getElementById("webBugCount").textContent, "1");
  // the tally with nothing in it stays hidden, and so does the break before it
  assert.equal(page.getElementById("blockedImgLine").hasAttribute("hidden"), true);
  assert.equal(page.getElementById("blockedImgCount").textContent, "");
  assert.equal(page.querySelector("#webBugs br"), null);
});

test("nothing is reported when there is nothing to report", () => {
  const page = doc(PAGE);
  const message = doc("<!DOCTYPE HTML><html><body>plain</body></html>");
  showCounts(page, blockedImages(message), webBugs(message));
  assert.equal(page.getElementById("blockedImages").hasAttribute("hidden"), true);
});

test("a tracking placeholder is given a box and an icon when it is shown", () => {
  const d = doc(MESSAGE);
  const bugs = webBugs(d);
  setHidden(bugs, false);
  assert.ok(bugs[0].style.background.includes(WEB_BUG_ICON), bugs[0].style.background);
  assert.equal(bugs[0].style.width, "32px");
  assert.equal(bugs[0].style.height, "32px");
  assert.equal(bugs[0].getAttribute("hidden"), null);
});

test("the toggle control is only offered when there is something to toggle", () => {
  const page = doc(PAGE);
  const frame = page.getElementById(FRAME_ID);
  const message = doc(MESSAGE);
  frame.contentDocument = message;
  createToggle(page, frame);
  assert.ok(page.getElementById("toggleBlockedImages"));

  const bare = doc(PAGE);
  bare.contentDocument = doc("<!DOCTYPE HTML><html><body>plain</body></html>");
  createToggle(bare, bare.getElementById(FRAME_ID));
  assert.equal(bare.getElementById("toggleBlockedImages"), null);
});

test("the frame height falls back to the body box when the marker cannot be measured", () => {
  const message = doc(MESSAGE);
  // linkedom has no layout, so the marker reports no box and the body is used
  assert.equal(typeof frameHeight(message), "number");
  assert.equal(frameHeight(doc("<!DOCTYPE HTML><html><body></body></html>")), 0);
  assert.equal(frameHeight(null), 0);
});

test("a frame whose document cannot be read is reported as unreadable, not thrown", () => {
  assert.equal(frameDocument(null), null);
  const page = doc(PAGE);
  const frame = page.getElementById(FRAME_ID);
  Object.defineProperty(frame, "contentDocument", {
    get() {throw new Error("SecurityError: the frame is not same-origin");}
  });
  assert.equal(frameDocument(frame), null);
});

test("decorating a frame does all of it, and leaves no script in the message", () => {
  const page = doc(PAGE);
  const frame = page.getElementById(FRAME_ID);
  const message = doc(MESSAGE);
  frame.contentDocument = message;
  decorateFrame(page, frame);
  assert.equal(message.querySelectorAll("script").length, 0);
  assert.equal(message.querySelectorAll("div")[0].getAttribute("style"), "color:red");
  assert.equal(message.querySelectorAll("img")[0].getAttribute("src"), BLOCKED_ICON);
  assert.equal(message.querySelectorAll("img")[0].style.display, "none");
  assert.equal(page.getElementById("blockedImgCount").textContent, "2");
  assert.ok(page.getElementById("toggleBlockedImages"));
});

test("a frame marked showBlockedImages leaves the images visible and offers no toggle", () => {
  const page = doc(PAGE);
  const frame = page.getElementById(FRAME_ID);
  frame.classList.add("showBlockedImages");
  const message = doc(MESSAGE);
  frame.contentDocument = message;
  decorateFrame(page, frame);
  assert.notEqual(message.querySelectorAll("img")[0].style.display, "none");
  assert.equal(page.getElementById("toggleBlockedImages"), null);
  assert.equal(page.getElementById("blockedImgCount").textContent, "2");
});

test("init wires up the frame that is already loaded, and does nothing without one", () => {
  const page = doc(PAGE);
  const frame = page.getElementById(FRAME_ID);
  frame.contentDocument = doc(MESSAGE);
  globalThis.document = page;
  globalThis.window = {addEventListener: function() {}};
  try {
    init();
    assert.equal(page.getElementById("blockedImgCount").textContent, "2");
  } finally {
    delete globalThis.document;
    delete globalThis.window;
  }
  const empty = doc("<!DOCTYPE HTML><html><body></body></html>");
  globalThis.document = empty;
  globalThis.window = {addEventListener: function() {}};
  try {
    init();
    assert.equal(empty.getElementById("blockedImgCount"), null);
  } finally {
    delete globalThis.document;
    delete globalThis.window;
  }
});
