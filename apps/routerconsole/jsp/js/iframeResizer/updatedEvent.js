/* I2P+ updatedEvent.js by dr|z3d */
/**
 * @module updatedEvent
 * @file updatedEvent.js - Framed-page signal that a new document has loaded.
 * @description Dispatches an "updated" event on the host page's document. The
 * console pages listen for it to re-adopt the frame and scroll both documents
 * to the top, which is what a framed page navigation looks like to the host.
 * Same-origin only: the host's document is reached directly.
 * @author dr|z3d
 * @license AGPL3 or later
 */

document.addEventListener("DOMContentLoaded", () => {
  window.parent.document.dispatchEvent(new Event("updated"));
});
