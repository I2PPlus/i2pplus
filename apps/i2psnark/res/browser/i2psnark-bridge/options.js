/**
 * @module i2psnarkBridgeOptions
 * @file options.js - I2PSnark Bridge options page logic.
 * @description Loads and saves the extension settings in chrome.storage.local:
 * the notification toggles, the .i2p-only restriction on magnet link handling,
 * and the optional pinned I2PSnark URL override. Changes are applied immediately
 * through the storage.onChanged listener in tagLinks.js and background.js, so no
 * reload of the extension is needed.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const DEFAULTS = {
    notifySuccess: true,
    notifyFailure: true,
    i2pOnly: true,
    overrideBaseUrl: ""
  };

  const CHECKBOXES = ["notifySuccess", "notifyFailure", "i2pOnly"];
  const TEXT_INPUTS = ["overrideBaseUrl"];
  const saved = document.getElementById("saved");

  function showSaved() {
    if (!saved) {return;}
    saved.style.visibility = "visible";
    setTimeout(() => {saved.style.visibility = "hidden";}, 1500);
  }

  function save() {
    const settings = {};
    for (const key of CHECKBOXES) {
      settings[key] = document.getElementById(key).checked;
    }
    for (const key of TEXT_INPUTS) {
      settings[key] = document.getElementById(key).value.trim();
    }
    chrome.storage.local.set(settings, showSaved);
  }

  chrome.storage.local.get(DEFAULTS, (stored) => {
    for (const key of CHECKBOXES) {
      const el = document.getElementById(key);
      if (!el) {continue;}
      el.checked = Boolean(stored[key]);
      el.addEventListener("change", save);
    }
    for (const key of TEXT_INPUTS) {
      const el = document.getElementById(key);
      if (!el) {continue;}
      el.value = stored[key] || "";
      el.addEventListener("change", save);
    }
  });
})();
