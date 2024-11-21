/* I2P+ I2PSnark toggleLinks.js by dr|z3d */
/* Toggle between magnet / torrent links and copy magnet links to clipboard */
/* License: AGPL3 or later */

"use strict";

const d = document;
const htmlTag = d.documentElement;
const toggleCss = d.head.querySelector("#toggleLinks");
const toggle = d.getElementById("linkswitch");
const toast = d.getElementById("toast");
const page = d.getElementById("page");

let linkToggleConfig = localStorage.getItem("linkToggle") || "magnets";
let magnetsVisible = false;

function initLinkToggler() {
  if (!toggle) { return; }

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

  function setLinkMode() {
    const isMagnetMode = linkToggleConfig === "magnets";
    toggle.checked = isMagnetMode;
    d.body.classList.toggle("magnets", isMagnetMode);
    d.body.classList.toggle("tlinks", !isMagnetMode);
  }

  function doToggle() {
    linkToggleConfig = linkToggleConfig === "magnets" ? "links" : "magnets";
    localStorage["linkToggle"] = linkToggleConfig;
    setLinkMode();
  }

  function showToast(msg) {
    toast.classList.remove("dismiss");
    toast.innerHTML = msg;
    toast.removeAttribute("hidden");
    scrollToTop(3500);
    setTimeout(() => { toast.classList.add("dismiss"); }, 3500);
  }

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