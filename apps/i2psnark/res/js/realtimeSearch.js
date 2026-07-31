/**
 * @module realtimeSearch
 * @file realtimeSearch.js - Real-time torrent search results as the user types.
 * @description Overrides the native #snarkSearch form submission with a debounced,
 * worker-backed search that filters the torrent list without page navigation. The
 * native GET form remains the no-JavaScript fallback.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {doRefresh, setActiveSearch} from "./refreshTorrents.js";

/**
 * @type {?HTMLFormElement}
 * @description The #snarkSearch form rendered when multiple torrents exist.
 */
const form = document.getElementById("snarkSearch");

/**
 * @type {?HTMLInputElement}
 * @description The search text input inside the search form.
 */
const input = form ? form.querySelector("#searchInput") : null;

/**
 * @type {?HTMLAnchorElement}
 * @description The clear-search link inside the search wrap.
 */
const clearLink = form ? form.querySelector("#searchwrap a") : null;

/**
 * @type {?number}
 * @description Timer id for the debounced search refresh.
 */
let debounceTimer = null;

/**
 * @type {?AbortController}
 * @description Aborts the previous in-flight search refresh when a newer query is typed.
 */
let searchController = null;

/**
 * @async
 * @function refreshWithSearch
 * @description Refreshes the torrent list with the given search term applied, aborting
 * any in-flight search refresh so stale results cannot overwrite newer ones.
 * @param {string} value - The search term; empty clears the filter.
 * @returns {Promise<void>}
 */
async function refreshWithSearch(value) {
  if (searchController) {searchController.abort();}
  searchController = new AbortController();
  setActiveSearch(value);
  try {
    await doRefresh({forceFetch: true, signal: searchController.signal});
  } catch (error) {
    if (error && error.name !== "AbortError") {console.error(error);}
  }
}

/**
 * @function initRealtimeSearch
 * @description Wires up the search input listener, form submit interception, and the
 * clear-search link. Seeds the active search from the server-rendered input value.
 * No-op when the search form is not rendered.
 * @returns {void}
 */
function initRealtimeSearch() {
  if (!form || !input) {return;}
  setActiveSearch(input.value.trim());
  input.addEventListener("input", () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {refreshWithSearch(input.value.trim());}, 250);
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    clearTimeout(debounceTimer);
    refreshWithSearch(input.value.trim());
  });
  if (clearLink) {
    clearLink.addEventListener("click", (event) => {
      event.preventDefault();
      input.value = "";
      clearTimeout(debounceTimer);
      refreshWithSearch("");
    });
  }
}

initRealtimeSearch();
