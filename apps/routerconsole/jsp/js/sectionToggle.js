/**
 * @module sectionToggle
 * @description Provides sidebar section toggle functionality for the I2P+ web console.
 * Manages expand/collapse state persistence and the click listeners for the
 * sidebar toggles. State application and storage are delegated to the
 * sidebarState.js classic script (window.applySidebarState /
 * window.setSidebarSectionState / window.readSidebarState /
 * window.saveSidebarState), which runs as soon as the sidebar markup is parsed
 * so collapsed sections never flash expanded, with a cookie fallback when
 * localStorage is unavailable.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import { stickySidebar } from "/js/stickySidebar.js";

const sb = document.getElementById("sidebar");

/** @type {HTMLElement|null} */
let listenersTarget = null;
/** @type {EventListener|null} */
let listenersHandler = null;

/**
 * Initializes sidebar section toggle controls, restoring persisted states
 * and attaching click listeners for toggle interactions.
 * @function sectionToggler
 * @returns {void}
 */
function sectionToggler() {
  const sb_wrap = document.getElementById("sb_wrap") || sb;
  const savedConfigs = typeof window.readSidebarState === "function" ? window.readSidebarState() : null;
  let sidebarSections = savedConfigs !== null ? savedConfigs : null;

  /**
   * Initializes storage with default sidebar section visibility states.
   * @function initializeLocalStorage
   * @returns {Promise<Object>} Resolves with the default state object
   */
  function initializeLocalStorage() {
    return new Promise((resolve) => {
      const defaultState = {
        advancedgeneral: false,
        advanced: false,
        bandwidth: false,
        general: false,
        help: false,
        internals: false,
        localtunnels: false,
        newsheadings: false,
        peers: false,
        queue: false,
        services: false,
        tunnels: false,
        updatesection: false
      };
      if (typeof window.saveSidebarState === "function") {
        window.saveSidebarState(defaultState);
      }
      resolve(defaultState);
    });
  }

  /**
   * Saves the current toggle state of the active element to storage.
   * @async
   * @function saveToggleStates
   * @returns {Promise<void>}
   */
  async function saveToggleStates() {
    const toggleInput = document.activeElement;
    if (toggleInput && toggleInput.id.startsWith("toggle_sb_")) {
      const key = toggleInput.id.replace("toggle_sb_", "");
      if (!savedConfigs) {
        sidebarSections = await initializeLocalStorage();
      }
      sidebarSections[key] = toggleInput.checked;
      if (typeof window.saveSidebarState === "function") {
        window.saveSidebarState(sidebarSections);
      }
    }
  }

  /**
   * Restores toggle states from storage, delegating to the sidebarState
   * helper that applied them before the document finished loading.
   * @function restoreToggleStates
   * @returns {void}
   */
  function restoreToggleStates() {
    if (typeof window.applySidebarState === "function") {
      window.applySidebarState();
    }
  }

  /**
   * Attaches delegated click listeners for toggle interactions on the sidebar.
   * @function addToggleListeners
   * @returns {void}
   */
  function addToggleListeners() {
    const handleToggle = (event) => {
      const id = event.target.id;
      if (!id.startsWith("toggle_sb_")) { return; }
      if (typeof window.setSidebarSectionState === "function") {
        window.setSidebarSectionState(event.target, event.target.checked);
      }
      saveToggleStates();
      stickySidebar();
    };
    // Keep exactly one delegated handler on sb_wrap at any time. The handler
    // is swapped each call so its captured element references are always the
    // freshest (refreshAll replaces the toggle nodes inside #xhr), while the
    // listener count never grows.
    if (listenersTarget && listenersHandler) {
      listenersTarget.removeEventListener("click", listenersHandler);
    }
    sb_wrap.addEventListener("click", handleToggle);
    listenersTarget = sb_wrap;
    listenersHandler = handleToggle;
  }

  restoreToggleStates();
  addToggleListeners();
}

/**
 * Counts news items in the sidebar and updates the news badge display.
 * @function countNewsItems
 * @returns {void}
 */
function countNewsItems() {
  const sbNewsHeadings = document.getElementById("sb_newsheadings");
  const newsBadge = document.getElementById("newsCount");
  const doubleCount = sb.querySelector("#newsCount+#newsCount");
  if (!sbNewsHeadings || !newsBadge) { return; }
  if (doubleCount) { doubleCount.remove(); }
  const newsCount = sbNewsHeadings.querySelectorAll("table tr").length;
  newsBadge.hidden = newsCount <= 0 || !sbNewsHeadings.classList.contains("collapsed");
  if (newsCount > 0 && newsBadge.innerHTML !== newsCount.toString()) {
    newsBadge.innerHTML = newsCount;
  }
}

export { sectionToggler, countNewsItems };
