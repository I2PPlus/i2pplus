/* I2P+ I2PSnark toggleDebug.js by dr|z3d */
/* Toggle debug table-rows and panel */
/* License: AGPL3 or later */

const DEBUG_STORAGE_KEY = "i2psnarkDebugMode";
const PREV_PEER_STATE_KEY = "i2psnarkPrevPeerState";

function getPeerParam() {
  const match = window.location.search.match(/[?&]p=([^&]*)/);
  return match ? match[1] : null;
}

function toggleDebug() {
  const bodyTag = document.body;
  const snarkFoot = document.getElementById("snarkFoot");
  if (!snarkFoot) {return;}

  const savedDebug = localStorage.getItem(DEBUG_STORAGE_KEY);
  const currentPeerParam = getPeerParam();
  const hasDebugParam = currentPeerParam === "2";

  // Restore debug mode if it was enabled and we're reloading
  if (savedDebug === "true" && !hasDebugParam && !bodyTag.classList.contains("debug")) {
    bodyTag.classList.add("debug");
    const separator = window.location.search ? "&" : "?";
    const newSearch = window.location.search + separator + "p=2";
    history.pushState(null, "", window.location.pathname + newSearch);
    document.dispatchEvent(new CustomEvent("debugToggled"));
  }

  document.addEventListener("click", debugListener);
}

let debugListener = function(event) {
  const bodyTag = document.body;
  if (event.target.id === "debugMode") {
    event.preventDefault();
    if (bodyTag.classList.contains("debug")) {
      // Turning debug OFF - restore previous peer state
      bodyTag.classList.remove("debug");
      localStorage.setItem(DEBUG_STORAGE_KEY, "false");

      const prevPeerState = localStorage.getItem(PREV_PEER_STATE_KEY);
      let newSearch;

      if (prevPeerState === "1") {
        // Restore p=1 (show all peers)
        newSearch = window.location.search.replace(/[?&]p=2/, "&p=1").replace(/^&/, "?");
      } else if (prevPeerState && prevPeerState !== "2") {
        // Restore specific torrent hash
        newSearch = window.location.search.replace(/[?&]p=2/, "&p=" + prevPeerState).replace(/^&/, "?");
      } else {
        // No previous peer state, remove p param entirely
        newSearch = window.location.search.replace(/[?&]p=2/, "").replace(/^&/, "?");
      }

      history.pushState(null, "", window.location.pathname + newSearch);
      localStorage.removeItem(PREV_PEER_STATE_KEY);
    } else {
      // Turning debug ON - save current peer state first
      const currentPeerParam = getPeerParam();
      if (currentPeerParam && currentPeerParam !== "2") {
        localStorage.setItem(PREV_PEER_STATE_KEY, currentPeerParam);
      }

      bodyTag.classList.add("debug");
      const separator = window.location.search ? "&" : "?";
      const newSearch = window.location.search + separator + "p=2";
      history.pushState(null, "", window.location.pathname + newSearch);
      localStorage.setItem(DEBUG_STORAGE_KEY, "true");
      document.dispatchEvent(new CustomEvent("debugToggled"));
    }
  }
};

document.addEventListener("DOMContentLoaded", toggleDebug);

export {toggleDebug};