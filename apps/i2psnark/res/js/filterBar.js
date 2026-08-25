/**
 * @module filterBar
 * @file filterBar.js - Setup I2PSnark torrent display filter buttons and AJAX filter loading.
 * @description Manages the filter bar UI for showing/hiding torrents by status (all, seeding,
 * downloading, etc.). Handles badge count display, filter state persistence to localStorage,
 * URL updates for sort icons, and AJAX-based loading of filtered content.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {doRefresh} from "./refreshTorrents.js"; // NOSONAR S1128
import {resolveFilterId} from "./uiLogic.js";

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
 * @type {boolean}
 * @description Guards against scheduling multiple badge count updates per animation frame.
 */
let countPending = false;

/**
 * @type {number}
 * @description Count of snark torrents matching the current filter.
 */
let snarkCount;

/**
 * @type {?string}
 * @description The id of the currently active filter, used to detect filter changes.
 */
let activeFilterId = null;

/**
 * @function scheduleBadgeUpdate
 * @description Coalesces torrent-count badge updates triggered by DOM mutations into a
 * single requestAnimationFrame callback. Re-queries the active filter's badge by id so
 * the observer stays valid across filter-bar re-renders.
 * @returns {void}
 */
function scheduleBadgeUpdate() {
  if (countPending) {return;}
  countPending = true;
  requestAnimationFrame(() => {
    countPending = false;
    const filterElement = document.getElementById(activeFilterId);
    const badge = filterElement ? filterElement.querySelector(".badge") : null;
    if (!filterElement || !badge) {return;}
    if (activeFilterId !== "all") {badge.textContent = countSnarks();}
    badge.hidden = false;
  });
}

/**
 * @function ensureObserver
 * @description Creates the badge-count MutationObserver once and attaches it to the
 * torrents container. Reused across showBadge calls because its callback reads module
 * state instead of capturing filter elements.
 * @returns {void}
 */
function ensureObserver() {
  if (observer || !torrents) {return;}
  observer = new MutationObserver(scheduleBadgeUpdate);
  observer.observe(torrents, { childList: true, subtree: true });
}

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

  const filterId = resolveFilterId(new URLSearchParams(window.location.search));

  const allFilters = filterbar.querySelectorAll(".filter");

  const activeFilter = document.getElementById(filterId) || filterbar.querySelector(".filter#all");
  if (!activeFilter) {return;}
  const filterChanged = activeFilterId !== activeFilter.id;
  activeFilterId = activeFilter.id;
  const activeBadge = activeFilter.querySelector(".badge");
  if (!activeBadge) {return;}
  // Idempotent writes only: skip attribute/class mutations when nothing changed so a
  // no-op refresh tick never dirties style or layout.
  if (activeBadge.id !== "filtercount") {activeBadge.id = "filtercount";}
  const activeHidden = activeFilter.id === "all" ? false : true;
  if (activeBadge.hidden !== activeHidden) {activeBadge.hidden = activeHidden;}

  allFilters.forEach(filter => {
    if (filter !== activeFilter) {
      if (filter.classList.contains("enabled")) {filter.classList.remove("enabled");}
      if (filterChanged) {
        filter.style.pointerEvents = "none";
        filter.style.opacity = ".5";
      }

      const badges = filter.querySelectorAll(".badge");
      badges.forEach(badge => {
        const filterAll = badge.closest(".filter#all");
        if (filterAll) {
          if (!badge.hidden) {badge.hidden = true;}
          if (badge.id !== "") {badge.id = "";}
        }
        else if (filter && filter.id !== "all") {
          if (!badge.hidden) {badge.hidden = true;}
          if (badge.textContent !== "") {badge.textContent = "";}
          if (badge.id !== "") {badge.id = "";}
        }
      });
    } else {
      ensureObserver();
    }

    if (filter !== activeFilter && filterChanged) {
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
 * @description Persists the current query string to localStorage when a sort is
 * applied. One capture-phase delegated listener on the document replaces the old
 * per-anchor binding: sort anchors are re-created whenever the header is refreshed,
 * which silently dropped per-element listeners, and N anchors need only one handler.
 * Capture phase guarantees the query string is read before snarkSort's bubble-phase
 * handler rewrites the URL via history.replaceState.
 * @returns {void}
 */
function updateURLs() {
  document.addEventListener("click", (event) => {
    if (event.target.closest(".sorter")) {setQuery();}
  }, {capture: true});

  /**
   * @function setQuery
   * @description Saves the current URL query string to localStorage for persistence across
   * page loads.
   * @returns {void}
   */
  function setQuery() {
    const params = window.location.search;
    if (params) {window.localStorage.setItem("queryString", params);}
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
      try {await doRefresh({url: xhrURL, forceFetch: true});}
      catch {}
      if (pagenavtop) {pagenavtop.hidden = filterElement.id !== "all";}
    }
  });
}

document.addEventListener("DOMContentLoaded", function() { updateURLs(); filterNav(); countSnarks(); showBadge(); });

export {updateURLs, showBadge};