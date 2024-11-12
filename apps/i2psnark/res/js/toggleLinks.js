/* I2P+ I2PSnark toggleLinks.js by dr|z3d */
/* Toggle between magnet / torrent links and copy magnet links to clipboard */
/* License: AGPL3 or later */

"use strict";

const toggleCss = document.head.querySelector("#toggleLinks");
const toggle = document.getElementById("linkswitch");
const toast = document.getElementById("toast");
const page = document.getElementById("page");

let linkToggleConfig = localStorage.getItem("linkToggle") || "magnets";
let magnetsVisible = false;

function initLinkToggler() {
  if (!toggle) { return; }

  function setLinkMode() {
    const isMagnetMode = linkToggleConfig === "magnets";
    toggle.checked = isMagnetMode;
    document.body.classList.toggle("magnets", isMagnetMode);
    document.body.classList.toggle("tlinks", !isMagnetMode);
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
    setTimeout(() => { toast.classList.add("dismiss"); }, 3000);
  }

  function copyMagnetHandler(event) {
    if (event.target.matches(".copyMagnet")) {
      document.body.classList.add("copyingToClipboard");
      event.preventDefault();
      event.stopPropagation();
      const anchor = event.target.closest("a").href;

      if (anchor && anchor.startsWith("magnet:?xt=urn:btih:")) {
        const tempInput = document.createElement("input");
        tempInput.value = anchor;
        document.body.appendChild(tempInput);
        tempInput.select();
        document.execCommand("copy");
        document.body.removeChild(tempInput);

        let magnetHash = anchor.substring(anchor.indexOf(":") + 1, anchor.indexOf("&"));
        let magnetName = anchor.substring(anchor.lastIndexOf("=") + 1);
        magnetName = decodeURIComponent(magnetName);
        showToast("Magnet link copied to clipboard: <b>" + magnetName + "</b><br>Hash: <b>" + magnetHash + "</b>");
        setTimeout(() => { document.body.classList.remove("copyingToClipboard"); }, 3000);
      } else {showToast("Invalid magnet link.");}
    }
  }

  setLinkMode();
  page.addEventListener("click", copyMagnetHandler);
  page.addEventListener("change", doToggle);
}

document.addEventListener("DOMContentLoaded", () => {
  initLinkToggler();
});