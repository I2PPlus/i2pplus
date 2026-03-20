/**
 * @module filterBar
 * @file filterBar.js - Setup I2PSnark torrent display filter buttons and AJAX filter loading.
 * @description Manages the filter bar UI for showing/hiding torrents by status (all, seeding,
 * downloading, etc.). Handles badge count display, filter state persistence to localStorage,
 * URL updates for sort icons, and AJAX-based loading of filtered content.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {refreshTorrents, doRefresh} from "./refreshTorrents.js";

/**
 * @type {?HTMLElement}
 * @description The #torrents container element.
 */
const torrents = document.getElementById("torrents");

/**
 * @type {?HTMLElement}
 * @description The filter bar element.
 */
let filterbar;

/**
 * @type {?MutationObserver}
 * @description MutationObserver watching the torrents container for child list changes.
 */
let observer;

/**
 * @type {number}
 * @description Count of snark torrents matching the current filter.
 */
let snarkCount;

/**
 * @async
 * @function showBadge
 * @description Updates the filter bar badge display based on the current URL filter/search
 * parameters. Highlights the active filter, disables inactive filters, sets up a
 * MutationObserver on the active filter to track torrent count changes, and persists
 * the active filter to localStorage.
 * @returns {Promise<void>}
 */
async function showBadge() {
  const filterbar = document.getElementById("filterBar");
  if (!filterbar) {return;}

  const urlParams = new URLSearchParams(window.location.search);
  const filterQuery = urlParams.get("filter");
  const searchQuery = urlParams.get("search");
  const filterId = searchQuery !== null ? "search" : (filterQuery || "all");

  const allFilters = filterbar.querySelectorAll(".filter");

  const activeFilter = document.getElementById(filterId);
  const activeBadge = activeFilter.querySelector(".badge");
  activeBadge.id = "filtercount";
  if (activeFilter.id === "all") {
    activeBadge.hidden = false;
    const allBadge = filterbar.querySelector(".filter#all .badge");
  } else {activeBadge.hidden = true;}

  allFilters.forEach(filter => {
    if (filter !== activeFilter) {
      filter.classList.remove("enabled");
      const tempDisabled = { pointerEvents: "none", opacity: ".5" };
      Object.assign(filter, tempDisabled);
      filter.style.pointerEvents = "none";
      filter.style.opacity = ".5";

      const badges = filter.querySelectorAll(".badge");
      badges.forEach(badge => {
        const filterAll = badge.closest(".filter#all");
        if (filterAll) { Object.assign(badge, { hidden: true, id: "" }); }
        else if (filter && filter.id !== "all") { Object.assign(badge, { hidden: true, textContent: "", id: "" }); }
      });
    } else {
      if (observer) {observer.disconnect();}
      observer = new MutationObserver(() => {
        if (activeFilter.id !== "all") {
          snarkCount = countSnarks();
          activeBadge.textContent = snarkCount;
        }
        activeBadge.hidden = false;
      });
      observer.observe(torrents, { childList: true, subtree: true });
    }

    if (filter !== activeFilter) {
      setTimeout(() => {
        filter.style.pointerEvents = "";
        filter.style.opacity = "";
      }, 1000);
    }
  });

  if (activeFilter) {
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
      window.localStorage.setItem("snarkFilter", activeFilter.id);
    }
  }
}

/**
 * @function countSnarks
 * @description Counts the number of visible torrent rows (volatile, non-peerinfo rows)
 * in the current table.
 * @returns {number} The count of visible torrent rows.
 */
function countSnarks() {
  return torrents?.querySelectorAll("#snarkTbody tr.volatile:not(.peerinfo)").length;
}

/**
 * @function updateURLs
 * @description Sets up click handlers on sort icon elements to save the current query string
 * to localStorage when a sort is applied.
 * @returns {void}
 */
function updateURLs() {
  const torrentform = document.getElementById("torrentlist");
  if (!torrentform) {return;}
  var xhrURL = "/i2psnark/.ajax/xhr1.html" + window.location.search;
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sorter");

  sortIcon.forEach((item) => { item.addEventListener("click", () => { setQuery(); }); });

  /**
   * @function setQuery
   * @description Saves the current URL query string to localStorage for persistence across
   * page loads.
   * @returns {void}
   */
  function setQuery() {
    const params = window.location.search;
    if (params) {const storage = window.localStorage.setItem("queryString", params);}
  }
}

/**
 * @async
 * @function filterNav
 * @description Sets up the filter bar click handler. Intercepts clicks on filter elements,
 * constructs an AJAX-compatible URL, updates the browser history, refreshes the badge,
 * and loads filtered content via doRefresh. Retries with a delay if the filter bar
 * is not yet available.
 * @returns {Promise<void>}
 */
async function filterNav() {
  const filterbar = document.getElementById("filterBar");
  if (!filterbar) { setTimeout(filterNav, 1500); return; }
  const pagenavtop = document.getElementById("pagenavtop");
  filterbar.addEventListener("click", async function(event) {
    const filterElement = event.target.closest(".filter");
    if (filterElement) {
      event.preventDefault();
      if (!filterElement.classList.contains("enabled")) {filterElement.classList.add("enabled");}
      const filterURL = new URL(filterElement.href);
      const xhrURL = "/i2psnark/.ajax/xhr1.html" + filterURL.search;
      history.replaceState({}, "", filterURL);
      showBadge();
      try {await doRefresh(xhrURL, true);}
      catch {}
      if (pagenavtop) {pagenavtop.hidden = filterElement.id !== "all";}
    }
  });
}

document.addEventListener("DOMContentLoaded", function() { updateURLs(); filterNav(); countSnarks(); showBadge(); });

export {updateURLs, showBadge};