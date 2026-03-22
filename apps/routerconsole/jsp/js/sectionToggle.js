/**
 * @module sectionToggle
 * @description Provides sidebar section toggle functionality for the I2P+ web console.
 * Manages expand/collapse state persistence via localStorage, badge visibility,
 * and specialized handling for services, updates, tunnels, and local tunnel sections.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import { stickySidebar } from "/js/stickySidebar.js";

const sb = document.getElementById("sidebar");

/**
 * Initializes sidebar section toggle controls, restoring persisted states
 * and attaching click listeners for toggle interactions.
 * @function sectionToggler
 * @returns {void}
 */
function sectionToggler() {
  const jobBadge = sb.querySelector('h3 a[href="/jobs"] .badge');
  const tunnelsBadge = sb.querySelector('h3 a[href="/tunnels"] .badge');
  const localtunnelSummary = document.getElementById("localtunnelSummary");
  const sb_advanced = document.getElementById("sb_advanced");
  const sb_advancedgeneral = document.getElementById("sb_advancedgeneral");
  const sb_bandwidth = document.getElementById("sb_bandwidth");
  const sb_general = document.getElementById("sb_general");
  const sb_graphstats = document.getElementById("sb_graphstats");
  const sb_help = document.getElementById("sb_help");
  const sb_internals = document.getElementById("sb_internals");
  const sb_localtunnels = document.getElementById("sb_localtunnels");
  const sb_newsH3 = document.getElementById("sb_newsH3");
  const sb_newsheadings = document.getElementById("sb_newsheadings");
  const sb_peers = document.getElementById("sb_peers");
  const sb_peers_condensed = document.getElementById("sb_peers_condensed");
  const sb_queue = document.getElementById("sb_queue");
  const sb_services = document.getElementById("sb_services");
  const sb_shortgeneral = document.getElementById("sb_shortgeneral");
  const sb_tunnels = document.getElementById("sb_tunnels");
  const sb_tunnels_condensed = document.getElementById("sb_tunnels_condensed");
  const sb_updatesection = document.getElementById("sb_updatesection");
  const sb_wrap = document.getElementById("sb_wrap") || sb;
  const xhr = document.getElementById("xhr");
  const savedConfigs = localStorage.getItem("sidebarSections");
  const main = document.querySelector(".main");
  const sidebarSections = savedConfigs !== null ? JSON.parse(savedConfigs) : null;

  const toggleElements = {
    toggle_sb_advancedgeneral: sb_advancedgeneral,
    toggle_sb_advanced: sb_advanced,
    toggle_sb_bandwidth: sb_bandwidth,
    toggle_sb_general: sb_general,
    toggle_sb_help: sb_help,
    toggle_sb_internals: sb_internals,
    toggle_sb_localtunnels: sb_localtunnels,
    toggle_sb_newsheadings: sb_newsheadings,
    toggle_sb_peers: sb_peers,
    toggle_sb_queue: sb_queue,
    toggle_sb_services: sb_services,
    toggle_sb_tunnels: sb_tunnels,
    toggle_sb_updatesection: sb_updatesection
  };

  let listenersAdded = false;

  /**
   * Initializes localStorage with default sidebar section visibility states.
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
      localStorage.setItem("sidebarSections", JSON.stringify(defaultState));
      resolve(defaultState);
    });
  }

  /**
   * Saves the current toggle state of the active element to localStorage.
   * @async
   * @function saveToggleStates
   * @returns {Promise<void>}
   */
  async function saveToggleStates() {
    const toggleInput = document.activeElement;
    if (toggleInput && toggleInput.id.startsWith("toggle_sb_")) {
      const key = toggleInput.id.replace("toggle_sb_", "");
      if (!savedConfigs) {
        await initializeLocalStorage();
      }
      sidebarSections[key] = toggleInput.checked;
      localStorage.setItem("sidebarSections", JSON.stringify(sidebarSections));
    }
  }

  /**
   * Restores toggle states from localStorage and applies visibility to sections.
   * @function restoreToggleStates
   * @returns {void}
   */
  function restoreToggleStates() {
    const sidebarSections = JSON.parse(localStorage.getItem("sidebarSections"));
    if (sidebarSections) {
      Object.entries(sidebarSections).forEach(([id, checked]) => {
        const toggleInput = document.getElementById(`toggle_sb_${id}`);
        if (toggleInput) {
          toggleInput.checked = checked;
          toggleElementVisibility(toggleInput, checked);
          const h3Element = toggleInput.closest("h3");
          const badge = toggleInput.parentElement.querySelector("a .badge");
          if (h3Element) {
            if (checked) {
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
      });
    }
  }

  /**
   * Attaches delegated click listeners for toggle interactions on the sidebar.
   * @function addToggleListeners
   * @returns {void}
   */
  function addToggleListeners() {
    if (listenersAdded) { return; }
    const handleToggle = (event) => {
      const id = event.target.id;
      if (!(id in toggleElements)) { return; }
      const currentState = event.target.checked;
      toggleElementVisibility(event.target, currentState);
      saveToggleStates();
      stickySidebar();
    };
    sb_wrap.addEventListener("click", handleToggle);
    listenersAdded = true;
  }

  /**
   * Toggles the visibility of a sidebar section and manages related elements.
   * @function toggleElementVisibility
   * @param {HTMLInputElement} toggleInput - The toggle checkbox input element
   * @param {boolean} isVisible - Whether the section should be visible
   * @returns {void}
   */
  function toggleElementVisibility(toggleInput, isVisible) {
    const element = toggleElements[toggleInput.id];
    if (!element) {
      return;
    }

    if (element.parentElement) {
      const titleH3 = element.parentElement.querySelector("h3");
      const badge = element.parentElement.querySelector("a .badge");
      element.hidden = !isVisible;
      const hr = sb.querySelector(`#${element.id}+hr`);

      if (element === sb_updatesection) {
        handleUpdateSectionVisibility(isVisible);
      } else if (element === sb_services) {
        handleServicesVisibility(isVisible);
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
        handleLocalTunnelsVisibility(isVisible);
      } else if (toggleInput.id === "toggle_sb_queue") {
        handleQueueVisibility(isVisible);
      } else if (toggleInput.id === "toggle_sb_tunnels") {
        handleTunnelsVisibility(isVisible);
      }

      if (element === sb_bandwidth && sb_graphstats) {
        sb_graphstats.style.opacity = sb_bandwidth.hidden ? "1" : null;
      }

      if (element === sb_internals || element === sb_advanced) {
        hr.hidden = false;
        hr.style.display = null;
      }
    }
  }

  /**
   * Handles the services section visibility, toggling between icon and text link display.
   * @function handleServicesVisibility
   * @param {boolean} isVisible - Whether the services section is expanded
   * @returns {void}
   */
  function handleServicesVisibility(isVisible) {
    if (sb_services !== null) {
      const hr = sb.querySelector("#sb_services.collapsed+hr");
      const toggleInput = document.getElementById("toggle_sb_services");
      const icons = sb_services.querySelectorAll(".sb_icon");
      const textLinks = sb_services.querySelectorAll("a:not(.sb_icon)");
      if (isVisible) {
        icons.forEach((icon) => {
          icon.hidden = true;
        });
        textLinks.forEach((link) => {
          link.hidden = null;
        });
        toggleInput.checked = true;
        sb_services.classList.remove("collapsed");
        toggleInput.closest("h3").classList.remove("collapsed");
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
        sb_services.classList.add("collapsed");
        toggleInput.closest("h3").classList.add("collapsed");
        if (hr !== null) {
          hr.hidden = true;
        }
      }
    }
  }

  /**
   * Handles the update section visibility and collapsed class toggling.
   * @function handleUpdateSectionVisibility
   * @param {boolean} isVisible - Whether the update section is expanded
   * @returns {void}
   */
  function handleUpdateSectionVisibility(isVisible) {
    if (sb_updatesection !== null) {
      sb_updatesection.hidden = false;
      const toggleInput = document.getElementById("toggle_sb_updatesection");
      if (isVisible) {
        toggleInput.checked = true;
        sb_updatesection.classList.remove("collapsed");
        sb_updatesection.querySelector("h3").classList.remove("collapsed");
      } else {
        toggleInput.checked = false;
        sb_updatesection.classList.add("collapsed");
        sb_updatesection.querySelector("h3").classList.add("collapsed");
      }
    }
  }

  const iconTypes = {
    server: "/themes/console/images/server.svg",
    client: "/themes/console/images/client.svg",
    snark: "/themes/console/images/snark.svg",
    i2pchat: "/themes/console/images/i2pchat.svg",
    ping: "/themes/console/images/ping.svg"
  };

  let cachedCounts = null;

  /**
   * Handles local tunnels section collapsed state with tunnel type count display.
   * @function handleLocalTunnelsVisibility
   * @param {boolean} isVisible - Whether the local tunnels section is expanded
   * @returns {void}
   */
  function handleLocalTunnelsVisibility(isVisible) {
    if (!localtunnelSummary) { return; }
    if (isVisible) {
      if (!localtunnelSummary.hidden) { localtunnelSummary.hidden = true; }
      return;
    }

    const container = sb_localtunnels;
    const newCounts = {};
    for (const key in iconTypes) {
      newCounts[key] = container.querySelectorAll(`img[src='${iconTypes[key]}']`).length;
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
   * Toggles the job queue badge visibility based on section state.
   * @function handleQueueVisibility
   * @param {boolean} isVisible - Whether the queue section is expanded
   * @returns {void}
   */
  function handleQueueVisibility(isVisible) {
    if (jobBadge) {
      jobBadge.hidden = isVisible;
    }
  }

  /**
   * Toggles the tunnels badge visibility based on section state.
   * @function handleTunnelsVisibility
   * @param {boolean} isVisible - Whether the tunnels section is expanded
   * @returns {void}
   */
  function handleTunnelsVisibility(isVisible) {
    if (tunnelsBadge) {
      tunnelsBadge.hidden = isVisible;
    }
  }
  restoreToggleStates();
  addToggleListeners();

  document.addEventListener("DOMContentLoaded", () => {
    setTimeout(() => { xhr.classList.remove("fadein"); }, 120);
  });
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