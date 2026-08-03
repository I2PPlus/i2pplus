/**
 * @module sidebarState
 * @description Applies the persisted sidebar section toggle state as soon as
 * the sidebar markup is parsed, before the document finishes loading, so
 * collapsed sections never flash expanded. Runs as a classic (non-module)
 * script from sidebar_noframe.jsi, immediately after the #xhr container.
 * Exposes applySidebarState(), setSidebarSectionState(), readSidebarState()
 * and saveSidebarState() on window so the sectionToggle.js module can
 * delegate state application and storage here, keeping a single source of
 * truth. State is read from localStorage with a cookie fallback.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

(function () {
  "use strict";

  const STORAGE_KEY = "sidebarSections";

  const iconTypes = {
    server: "/themes/console/images/server.svg",
    client: "/themes/console/images/client.svg",
    snark: "/themes/console/images/snark.svg",
    i2pchat: "/themes/console/images/i2pchat.svg",
    ping: "/themes/console/images/ping.svg"
  };

  let cachedCounts = null;

  /**
   * Reads the saved section state, preferring localStorage and falling back
   * to the cookie when it is unavailable or empty.
   * @function readSidebarState
   * @returns {Object|null} The saved state object, or null if none is stored
   */
  function readSidebarState() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved !== null) {
        return JSON.parse(saved);
      }
    } catch (e) {
      // localStorage unavailable; fall through to the cookie
    }
    return readCookieState();
  }

  /**
   * Persists the section state to localStorage and mirrors it to the
   * sidebarSections cookie for when localStorage is unavailable.
   * @function saveSidebarState
   * @param {Object} state - The sidebar section visibility state
   * @returns {void}
   */
  function saveSidebarState(state) {
    const serialized = JSON.stringify(state);
    try {
      localStorage.setItem(STORAGE_KEY, serialized);
    } catch (e) {
      // localStorage unavailable; the cookie below still persists the state
    }
    try {
      document.cookie = STORAGE_KEY + "=" + encodeURIComponent(serialized) +
                        "; Path=/; SameSite=Lax; Max-Age=31536000";
    } catch (e) {
      // nothing more we can do
    }
  }

  /**
   * Reads the section state from the sidebarSections cookie.
   * @function readCookieState
   * @returns {Object|null} The saved state object, or null if none is stored
   */
  function readCookieState() {
    const match = document.cookie.match(/(?:^|;\s*)sidebarSections=([^;]*)/);
    if (!match) { return null; }
    try {
      return JSON.parse(decodeURIComponent(match[1]));
    } catch (e) {
      return null;
    }
  }

  /**
   * Toggles the services section between icon and text link display.
   * @function handleServicesVisibility
   * @param {HTMLInputElement} toggleInput - The toggle checkbox input element
   * @param {HTMLElement} element - The services section element
   * @param {boolean} isVisible - Whether the section should be visible
   * @returns {void}
   */
  function handleServicesVisibility(toggleInput, element, isVisible) {
    const sb = document.getElementById("sidebar");
    const hr = sb ? sb.querySelector("#sb_services.collapsed+hr") : null;
    const icons = element.querySelectorAll(".sb_icon");
    const textLinks = element.querySelectorAll("a:not(.sb_icon)");
    if (isVisible) {
      icons.forEach((icon) => {
        icon.hidden = true;
      });
      textLinks.forEach((link) => {
        link.hidden = null;
      });
      toggleInput.checked = true;
      element.classList.remove("collapsed");
      const h3 = toggleInput.closest("h3");
      if (h3) { h3.classList.remove("collapsed"); }
      if (hr !== null) {
        hr.hidden = null;
      }
    } else {
      icons.forEach((icon) => {
        icon.hidden = null;
      });
      textLinks.forEach((link) => {
        link.hidden = true;
      });
      toggleInput.checked = false;
      element.classList.add("collapsed");
      const h3 = toggleInput.closest("h3");
      if (h3) { h3.classList.add("collapsed"); }
      if (hr !== null) {
        hr.hidden = true;
      }
    }
  }

  /**
   * Toggles the update section visibility and collapsed class.
   * @function handleUpdateSectionVisibility
   * @param {HTMLInputElement} toggleInput - The toggle checkbox input element
   * @param {HTMLElement} element - The update section element
   * @param {boolean} isVisible - Whether the section should be visible
   * @returns {void}
   */
  function handleUpdateSectionVisibility(toggleInput, element, isVisible) {
    element.hidden = false;
    if (isVisible) {
      toggleInput.checked = true;
      element.classList.remove("collapsed");
      const h3 = element.querySelector("h3");
      if (h3) { h3.classList.remove("collapsed"); }
    } else {
      toggleInput.checked = false;
      element.classList.add("collapsed");
      const h3 = element.querySelector("h3");
      if (h3) { h3.classList.add("collapsed"); }
    }
  }

  /**
   * Refreshes the local tunnels summary counts when the section is collapsed.
   * @function handleLocalTunnelsVisibility
   * @param {HTMLElement} element - The local tunnels section element
   * @param {boolean} isVisible - Whether the section is expanded
   * @returns {void}
   */
  function handleLocalTunnelsVisibility(element, isVisible) {
    const localtunnelSummary = document.getElementById("localtunnelSummary");
    if (!localtunnelSummary) { return; }
    if (isVisible) {
      if (!localtunnelSummary.hidden) { localtunnelSummary.hidden = true; }
      return;
    }

    const newCounts = {};
    for (const key in iconTypes) {
      newCounts[key] = element.querySelectorAll(`img[src='${iconTypes[key]}']`).length;
    }

    if (cachedCounts && Object.entries(newCounts).every(([key, val]) => cachedCounts[key] === val)) {
      if (localtunnelSummary.hidden) { localtunnelSummary.hidden = false; }
      return;
    }

    cachedCounts = newCounts;
    const row = localtunnelSummary.querySelector("tr#localtunnelsActive");
    if (!row) { return; }
    const cell = row.querySelector("td");
    if (!cell) { return; }

    const fragment = document.createDocumentFragment();
    for (const [type, count] of Object.entries(newCounts)) {
      if (count === 0) continue;
      const span = document.createElement("span");
      span.className = `count_${count}`;
      const img = document.createElement("img");
      img.src = iconTypes[type];
      img.alt = `${type} tunnel icon`;
      span.appendChild(document.createTextNode(`${count} x `));
      span.appendChild(img);
      fragment.appendChild(span);
    }
    cell.innerHTML = "";
    cell.appendChild(fragment);
    if (localtunnelSummary.hidden) { localtunnelSummary.hidden = false; }
  }

  /**
   * Applies the visibility of a sidebar section from its toggle input state.
   * @function setSidebarSectionState
   * @param {HTMLInputElement} toggleInput - The toggle checkbox input element
   * @param {boolean} isVisible - Whether the section should be visible
   * @returns {void}
   */
  function setSidebarSectionState(toggleInput, isVisible) {
    const element = document.getElementById(toggleInput.id.replace("toggle_sb_", "sb_"));
    if (!element || !element.parentElement) { return; }
    const sb = document.getElementById("sidebar");
    const hr = sb ? sb.querySelector(`#${element.id}+hr`) : null;

    element.hidden = !isVisible;

    if (element.id === "sb_updatesection") {
      handleUpdateSectionVisibility(toggleInput, element, isVisible);
    } else if (element.id === "sb_services") {
      handleServicesVisibility(toggleInput, element, isVisible);
    } else if (isVisible) {
      if (hr) {
        hr.hidden = false;
        hr.style.display = null;
      }
      toggleInput.checked = true;
      if (element.classList) { element.classList.remove("collapsed"); }
    } else {
      if (hr) {
        hr.hidden = true;
        hr.style.display = "none";
      }
      toggleInput.checked = false;
      if (element.classList) { element.classList.add("collapsed"); }
    }

    if (toggleInput.id === "toggle_sb_localtunnels") {
      handleLocalTunnelsVisibility(element, isVisible);
    } else if (toggleInput.id === "toggle_sb_queue") {
      const jobBadge = sb ? sb.querySelector('h3 a[href="/jobs"] .badge') : null;
      if (jobBadge) { jobBadge.hidden = isVisible; }
    } else if (toggleInput.id === "toggle_sb_tunnels") {
      const tunnelsBadge = sb ? sb.querySelector('h3 a[href="/tunnels"] .badge') : null;
      if (tunnelsBadge) { tunnelsBadge.hidden = isVisible; }
    }

    if (element.id === "sb_bandwidth") {
      const graphstats = document.getElementById("sb_graphstats");
      if (graphstats) { graphstats.style.opacity = element.hidden ? "1" : null; }
    }
    if ((element.id === "sb_internals" || element.id === "sb_advanced") && hr) {
      hr.hidden = false;
      hr.style.display = null;
    }

    const h3Element = toggleInput.closest("h3");
    const badge = toggleInput.parentElement.querySelector("a .badge");
    if (h3Element) {
      if (isVisible) {
        h3Element.classList.remove("collapsed");
        if (badge) {
          requestAnimationFrame(() => {
            badge.hidden = true;
          });
        }
      } else {
        h3Element.classList.add("collapsed");
        if (badge) {
          badge.removeAttribute("hidden");
        }
      }
    }
  }

  /**
   * Restores all sidebar section toggle states from storage.
   * @function applySidebarState
   * @returns {void}
   */
  function applySidebarState() {
    const sidebarSections = readSidebarState();
    if (!sidebarSections) { return; }
    Object.entries(sidebarSections).forEach(([id, checked]) => {
      const toggleInput = document.getElementById(`toggle_sb_${id}`);
      if (toggleInput) {
        toggleInput.checked = checked;
        setSidebarSectionState(toggleInput, checked);
      }
    });
  }

  window.applySidebarState = applySidebarState;
  window.setSidebarSectionState = setSidebarSectionState;
  window.readSidebarState = readSidebarState;
  window.saveSidebarState = saveSidebarState;

  applySidebarState();
})();
