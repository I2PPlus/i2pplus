/**
 * @module toggleLinks
 * @file toggleLinks.js - Toggle between magnet/torrent links and copy magnet links to clipboard.
 * @description Provides UI controls for switching between magnet link and direct torrent link
 * display modes in I2PSnark. Also enables copying magnet links to the system clipboard with
 * a toast notification on success.
 * @author dr|z3d
 * @license AGPL3 or later
 */

"use strict";

/**
 * @type {Document}
 * @description Shorthand reference to the global document object.
 */
const d = document;

/**
 * @type {HTMLElement}
 * @description The root HTML element of the document.
 */
const htmlTag = d.documentElement;

/**
 * @type {?HTMLStyleElement}
 * @description The style element controlling link display toggling.
 */
const toggleCss = d.head.querySelector("#toggleLinks");

/**
 * @type {?HTMLInputElement}
 * @description The checkbox input controlling magnet/link display mode.
 */
const toggle = d.getElementById("linkswitch");

/**
 * @type {?HTMLElement}
 * @description The toast notification element for clipboard copy feedback.
 */
const toast = d.getElementById("toast");

/**
 * @type {?HTMLElement}
 * @description The main page container element.
 */
const page = d.getElementById("page");

/**
 * @type {string}
 * @description Current link display mode: "magnets" or "links". Persisted in localStorage.
 */
let linkToggleConfig = localStorage.getItem("linkToggle") || "magnets";

/**
 * @type {boolean}
 * @description Whether magnet links are currently visible in the UI.
 */
let magnetsVisible = false;

/**
 * @function initLinkToggler
 * @description Initializes the link toggling system. Sets up event listeners for the
 * toggle checkbox, magnet link copying, and toast notifications. Manages scroll position
 * preservation during link mode changes.
 * @returns {void}
 */
function initLinkToggler() { // NOPMD - ConsistentReturn (nested scrollToTop return is misdetected)
  if (!toggle) { return; }

  /**
   * @async
   * @function scrollToTop
   * @description Scrolls to the top of the window (and parent window if iframed),
   * waits for the specified timeout, then smoothly scrolls back to the original position.
   * Used to ensure toast notifications are visible.
   * @param {number} timeout - Delay in milliseconds before scrolling back to original position.
   * @returns {Promise<void>}
   */
  async function scrollToTop(timeout) {
    const X = window.pageXOffset, Y = window.pageYOffset;
    const iframed = htmlTag.classList.contains("iframed") || window.top !== parent.window.top;
    const delay = iframed ? 3500 : 3750;
    return new Promise((resolve) => {
      window.scrollTo(0, 0);
      let parentX, parentY;
      if (iframed) {
        parentX = parent.window.pageXOffset;
        parentY = parent.window.pageYOffset;
        parent.window.scrollTo(0, 0);
      }
      const scrollToOriginal = () => {
        window.scrollTo({ top: Y, left: X, behavior: "smooth" });
        if (iframed) {
          parent.window.scrollTo({ top: parentY, left: parentX, behavior: "smooth" });
        }
        resolve();
      };
      setTimeout(scrollToOriginal, timeout);
    });
  }

  /**
   * @function setLinkMode
   * @description Applies the current link toggle configuration to the UI by setting
   * body classes and the toggle checkbox state.
   * @returns {void}
   */
  function setLinkMode() {
    const isMagnetMode = linkToggleConfig === "magnets";
    toggle.checked = isMagnetMode;
    d.body.classList.toggle("magnets", isMagnetMode);
    d.body.classList.toggle("tlinks", !isMagnetMode);
  }

  /**
   * @function doToggle
   * @description Toggles between magnet and link display modes, persists the choice
   * to localStorage, and updates the UI.
   * @returns {void}
   */
  function doToggle() {
    linkToggleConfig = linkToggleConfig === "magnets" ? "links" : "magnets";
    localStorage["linkToggle"] = linkToggleConfig;
    setLinkMode();
  }

  /**
   * @function showToast
   * @description Displays a toast notification with the given message, auto-dismisses
   * after 3.5 seconds, and scrolls to ensure visibility.
   * @param {string} msg - The HTML message to display in the toast.
   * @returns {void}
   */
  function showToast(msg) {
    toast.classList.remove("dismiss");
    toast.innerHTML = msg;
    toast.removeAttribute("hidden");
    scrollToTop(3500);
    setTimeout(() => { toast.classList.add("dismiss"); }, 3500);
  }

  /**
   * @function copyMagnetHandler
   * @description Handles click events on .copyMagnet elements. Extracts the magnet link
   * from the anchor's href, copies it to the clipboard, and shows a confirmation toast.
   * @param {MouseEvent} event - The click event.
   * @returns {void}
   */
  function copyMagnetHandler(event) {
    if (event.target.matches(".copyMagnet")) {
      d.body.classList.add("copyingToClipboard");
      event.preventDefault();
      event.stopPropagation();
      const anchor = event.target.closest("a").href;

      if (anchor && anchor.startsWith("magnet:?xt=urn:btih:")) {
        copyToClipboard(anchor);
        let magnetHash = anchor.substring(anchor.indexOf(":") + 1, anchor.indexOf("&"));
        let magnetName = anchor.substring(anchor.lastIndexOf("=") + 1);
        magnetName = decodeURIComponent(magnetName);
        showToast("Magnet link copied to clipboard: <b>" + magnetName + "</b><br>Hash: <b>" + magnetHash + "</b>");
        setTimeout(() => { d.body.classList.remove("copyingToClipboard"); }, 4000);
      } else {showToast("Invalid magnet link.");}
    }
  }

  /**
   * @async
   * @function copyToClipboard
   * @description Copies the given text to the system clipboard using the Clipboard API.
   * Silently handles permission or API errors.
   * @param {string} text - The text to copy to the clipboard.
   * @returns {Promise<void>}
   */
  async function copyToClipboard(text) {
    try { await navigator.clipboard.writeText(text); }
    catch (error) {}
  }

  setLinkMode();
  page.addEventListener("click", copyMagnetHandler);
  page.addEventListener("change", (event) => {
    if (event.target.id === "linkswitch") { doToggle(); }
  });

}

d.addEventListener("DOMContentLoaded", () => {
  initLinkToggler();
});