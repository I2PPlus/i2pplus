/**
 * Tier-2 contract tests for the inline text viewer in
 * apps/i2psnark/res/js/textView.js, run against the harness browser shim with
 * fetch stubbed to return the file (no browser needed).
 *
 * These pin what the viewer does with a torrent's own text: the file's bytes go in
 * as text nodes, never as markup. Parsing them as HTML and writing the result back
 * with innerHTML turned `&lt;b&gt;` in a .txt or .nfo into a live element of the
 * console page, so whoever made the torrent could put markup - and a frame that
 * loads a second file from the same torrent - onto the listing.
 *
 * The shim answers a missing getElementById with an inert stand-in rather than
 * null, so the assertions below count elements inside the viewer instead of
 * looking the injected ids up.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { installBrowserShim } from "../helpers/browserShim.js";

// dom -> js -> test -> tools -> repo root
const SCRIPT = join(import.meta.dirname, "..", "..", "..", "..",
  "apps", "i2psnark", "res", "js", "textView.js");

/**
 * Listing row as the servlet's renderFileRow writes it for a complete text file.
 *
 * @param {string} name - the file name shown in the row, which sets the extension
 * @returns {string} the fixture body
 */
const fixture = name => `<table><tbody>
<tr><td class="fileIcon text"><a href="${name}"><img alt="" src="txt.png"></a></td>
<td class="snarkFileName"><a href="${name}">${name}</a></td></tr>
</tbody></table>`;

/**
 * Loads a fresh copy of the viewer against the shimmed document, opens the viewer
 * for the row's file, and waits for the fetched body to be rendered. The shim's
 * fetch never resolves and its timers never fire, so both are replaced for the
 * duration of the case and put back afterwards.
 *
 * @param {string} name - the file name in the row, which sets the extension
 * @param {string} body - the bytes the served file holds
 * @returns {Promise<{content: Object}>} the viewer's content element
 */
async function openViewer(name, body) {
  installBrowserShim();
  document.body.innerHTML = fixture(name);
  const savedFetch = globalThis.fetch;
  globalThis.fetch = () => Promise.resolve({text: () => Promise.resolve(body)});
  try {
    await import(`${pathToFileURL(SCRIPT).href}?case=${Math.random()}`);
    document.dispatchEvent(new window.Event("DOMContentLoaded", {bubbles: true}));
    document.querySelector("td.fileIcon.text > a")
      .dispatchEvent(new window.Event("click", {bubbles: true, cancelable: true}));
    // the fetch chain is three microtask hops deep; timers are stubbed, so wait
    // on the microtask queue rather than setTimeout
    for (let i = 0; i < 8; i++) {await Promise.resolve();}
    return {content: document.getElementById("textview-content")};
  } finally {
    globalThis.fetch = savedFetch;
  }
}

/**
 * The viewer's own content, without the filename header that sits beside it.
 *
 * @param {Object} content - the #textview-content element
 * @returns {string} the text the file's bytes became
 */
const bodyText = content => content.lastChild.textContent;

test("entity-encoded markup in a torrent .txt stays text", async () => {
  const {content} = await openViewer("a.txt",
    "Release notes\n&lt;b id=marker&gt;MARKER&lt;/b&gt;\n&lt;iframe id=frm src=carrier.html&gt;&lt;/iframe&gt;\n");
  // the file's text is one text node, and no frame or element came out of it
  assert.equal(content.lastChild.nodeType, 3);
  assert.equal(content.querySelectorAll("iframe").length, 0);
  assert.ok(bodyText(content).includes("&lt;b id=marker&gt;MARKER&lt;/b&gt;"));
});

test("a file that is only markup is still shown as text", async () => {
  // the old code handed the raw file to innerHTML whenever the parsed body had no
  // text to read back, which is exactly the case of a file that is all tags
  const {content} = await openViewer("c.txt", '<img id=marker3 alt="">');
  // the header's own file icon is the only image in the viewer
  assert.equal(content.querySelectorAll("img").length, 1);
  assert.equal(bodyText(content), '<img id=marker3 alt="">');
});

test("a raw tag in a .nfo is not stripped from the display", async () => {
  const {content} = await openViewer("b.nfo", "NFO\n<b id=marker2>M2</b>\n");
  assert.equal(content.querySelectorAll("b").length, 0);
  // a pre-formatted type has its spaces replaced by hard spaces, so the tag's
  // spaces are NBSP here - what matters is that the tag is text, not an element
  assert.ok(bodyText(content).includes("<b\u00A0id=marker2>M2</b>"));
});

test("the line-numbered view builds list items out of text nodes", async () => {
  const {content} = await openViewer("s.js", "one\n<b id=marker4>M4</b>\nthree");
  const items = content.querySelectorAll("ol li");
  assert.equal(items.length, 3);
  assert.deepEqual([...items].map(li => li.textContent),
    ["one", "<b\u00A0id=marker4>M4</b>", "three"]);
  assert.ok([...items].every(li => li.childNodes.length === 1 && li.firstChild.nodeType === 3));
  assert.ok(content.classList.contains("lines"));
});

test("the filename header survives the render and the spaces stay hard", async () => {
  const {content} = await openViewer("n.nfo", "two words");
  assert.equal(content.firstElementChild.id, "viewerFilename");
  assert.ok(content.firstElementChild.textContent.includes("n.nfo"));
  assert.ok(bodyText(content).includes("two\u00A0words"));
});
