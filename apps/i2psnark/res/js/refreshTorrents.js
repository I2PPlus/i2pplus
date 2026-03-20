/**
 * @module refreshTorrents
 * @file refreshTorrents.js - Selectively refreshes torrents and volatile elements in the I2PSnark UI.
 * @description Core refresh module for I2PSnark. Manages periodic AJAX-based updates of the
 * torrent list, screen log, header/footer stats, and file listings. Uses a web worker for
 * fetch operations, caches responses, handles visibility changes, and orchestrates
 * initialization of sub-modules (sorting, pagination, filter bar, lightbox, debug toggle).
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {MESSAGE_TYPES} from "./messageTypes.js";
import {pageNav} from "./pageNav.js";
import {showBadge} from "./filterBar.js";
import {snarkSort} from "./snarkSort.js";
import {toggleDebug} from "./toggleDebug.js";
import {Lightbox} from "./lightbox.js";
import {initSnarkAlert} from "./snarkAlert.js";

/**
 * @type {Map<string, Object>}
 * @description In-memory cache for fetched HTML documents, keyed by URL.
 */
const cache = new Map();

/**
 * @type {number}
 * @description Duration in milliseconds before cached documents are considered stale.
 */
const cacheDuration = 5000;

/**
 * @type {?HTMLElement}
 * @description The #debugMode element, used to check if debug mode is active.
 */
const debugMode = document.getElementById("debugMode");

/**
 * @type {?HTMLElement}
 * @description The #dirInfo element for file directory listings.
 */
const files = document.getElementById("dirInfo");

/**
 * @type {?HTMLElement}
 * @description The #filterBar element for torrent status filtering.
 */
const filterbar = document.getElementById("filterBar");

/**
 * @type {?HTMLElement}
 * @description The main navigation link element (.nav_main) in the navbar.
 */
const home = document.querySelector("#navbar .nav_main");

/**
 * @type {boolean}
 * @description Whether the page is running inside an iframe.
 */
const isIframed = document.documentElement.classList.contains("iframed") || window.parent;

/**
 * @type {boolean}
 * @description Whether the page is running as a standalone I2PSnark instance.
 */
const isStandalone = document.documentElement.classList.contains("standalone");

/**
 * @type {?HTMLElement}
 * @description The #mainsection container element.
 */
const mainsection = document.getElementById("mainsection");

/**
 * @type {?HTMLElement}
 * @description The #noTorrents element shown when no torrents are present.
 */
const noTorrents = document.getElementById("noTorrents");

/**
 * @type {Document}
 * @description Reference to the parent document (for iframe mode).
 */
const parentDoc = window.parent.document;

/**
 * @type {string}
 * @description The current URL query string.
 */
const query = window.location.search;

/**
 * @type {?HTMLElement}
 * @description The #screenlog element for displaying operation messages.
 */
const screenlog = document.getElementById("screenlog");

/**
 * @type {?HTMLElement}
 * @description The #snarkHead element for the torrent table header.
 */
const snarkHead = document.getElementById("snarkHead");

/**
 * @type {?string}
 * @description The stored refresh interval from localStorage.
 */
const storageRefresh = localStorage.getItem("snarkRefresh");

/**
 * @type {?HTMLElement}
 * @description The #torrents container element.
 */
const torrents = document.getElementById("torrents");

/**
 * @type {?HTMLElement}
 * @description The #snarkTbody element containing torrent table rows.
 */
const torrentsBody = document.getElementById("snarkTbody");

/**
 * @type {?HTMLElement}
 * @description The #torrentlist form element.
 */
const torrentForm = document.getElementById("torrentlist");

/**
 * @type {boolean}
 * @description Whether the chimp image has been preloaded and cached.
 */
let chimpIsCached = false;

/**
 * @type {boolean}
 * @description Whether the server is currently unreachable.
 */
let noConnection = false;

/**
 * @type {?number}
 * @description Interval ID for the main torrent refresh timer.
 */
let snarkRefreshIntervalId;

/**
 * @type {?number}
 * @description Interval ID for the screen log refresh timer.
 */
let screenLogIntervalId;

/**
 * @type {boolean}
 * @description Whether debug logging is enabled.
 */
let debugging = false;

/**
 * @type {boolean}
 * @description Whether initial setup has been completed.
 */
let initialized = false;

/**
 * @type {boolean}
 * @description Whether the document is currently visible (not hidden by tab switch).
 */
let isDocumentVisible = true;

/**
 * @type {number}
 * @description Timestamp of the last server connectivity check.
 */
let lastCheckTime = 0;

/**
 * @type {boolean}
 * @description Whether a full refresh of the torrent table is required.
 */
let requireFullRefresh = true;

/**
 * @function requestAnimationFramePromise
 * @description Wraps requestAnimationFrame in a Promise, executing the callback within
 * the animation frame. Provides a cancel method to abort pending frames.
 * @param {Function} callback - The function to execute within the animation frame.
 * @returns {Promise<void>} A promise that resolves after the callback executes.
 * @example
 * await requestAnimationFramePromise(() => { element.textContent = "updated"; });
 */
const requestAnimationFramePromise = (callback) => {
  let requestId;
  const execCallback = () => {
    try {
      callback();
    } catch (error) {
      if (debugging) console.error(error);
    } finally {
      cancelAnimationFrame(requestId);
    }
  };

  const promise = new Promise((resolve) => {
    const clear = () => {
      cancelAnimationFrame(requestId);
      resolve();
    };

    requestId = requestAnimationFrame(execCallback);

    if (!requestAnimationFramePromise.cancel) {
      requestAnimationFramePromise.cancel = clear;
    }

    return clear;
  });

  return promise;
};

/**
 * @async
 * @function getRefreshInterval
 * @description Reads the refresh interval from the global snarkRefreshDelay variable,
 * localStorage, or defaults to 5 seconds. Persists the value to localStorage and
 * returns it in milliseconds.
 * @returns {Promise<number>} The refresh interval in milliseconds.
 */
async function getRefreshInterval() {
  const refreshInterval = snarkRefreshDelay || parseInt(localStorage.getItem("snarkRefreshDelay")) || 5;
  localStorage.setItem("snarkRefresh", refreshInterval);
  return refreshInterval * 1000;
}

/**
 * @async
 * @function getURL
 * @description Constructs the AJAX refresh URL by replacing "/i2psnark/" with
 * "/i2psnark/.ajax/xhr1.html" in the current page URL.
 * @returns {Promise<string>} The AJAX-compatible refresh URL.
 */
async function getURL() { return window.location.href.replace("/i2psnark/", "/i2psnark/.ajax/xhr1.html"); }

/**
 * @async
 * @function setLinks
 * @description Updates the main navigation link href to point to the I2PSnark root,
 * optionally including a query string for filter preservation.
 * @param {string} [query] - The query string to append (e.g., "?filter=active").
 * @returns {Promise<void>}
 */
async function setLinks(query) { if (home) {home.href = query ? `/i2psnark/${query}` : "/i2psnark/";} }

/**
 * @async
 * @function initHandlers
 * @description Initializes sub-module handlers including sort listeners, debug toggle,
 * page navigation, and filter badge display. Uses requestAnimationFrame for smooth UI.
 * @returns {Promise<void>}
 */
async function initHandlers() {
  if (torrents) {
    snarkSort();
    if (debugMode) {toggleDebug();}
  }
  setLinks();
  await requestAnimationFramePromise(async () => {
    if (document.getElementById("pagenavtop")) await pageNav();
    if (filterbar) await showBadge();
    if (debugging) console.log("initHandlers()");
  });
}

/**
 * @async
 * @function updateElement
 * @description Updates a DOM element's content if it differs from the response element's content.
 * Compares trimmed content of the specified property and only updates if changed.
 * @param {?HTMLElement} elem - The DOM element to update.
 * @param {?HTMLElement} respElem - The response element containing the new content.
 * @param {string} [property="innerHTML"] - The property to compare and update (e.g., "innerHTML", "textContent").
 * @returns {Promise<void>}
 */
async function updateElement(elem, respElem, property = "innerHTML") {
  if (elem && respElem) {
    const currentContent = elem[property].trim();
    const newContent = respElem[property].trim();
    if (currentContent !== newContent) {elem[property] = newContent;}
  }
}

/**
 * @type {Worker}
 * @description Web Worker instance for offloading fetch requests.
 */
const worker = new Worker("/i2psnark/.res/js/snarkWork.js");

/**
 * @type {DOMParser}
 * @description Reusable DOMParser instance for parsing fetched HTML.
 */
const parser = new DOMParser();

/**
 * @type {HTMLDivElement}
 * @description Reusable container element for parsing response HTML.
 */
const container = document.createElement("div");

/**
 * @type {AbortController}
 * @description Controller for aborting in-progress fetch requests.
 */
let abortController = new AbortController();

/**
 * @type {Map<string, Promise>}
 * @description Map of ongoing fetch promises keyed by URL, used to deduplicate concurrent requests.
 */
const ongoingRequests = new Map();

/**
 * @async
 * @function fetchHTMLDocument
 * @description Fetches an HTML document from the given URL, parsing it into a Document.
 * Uses a cache layer to avoid redundant fetches within the cacheDuration window.
 * Deduplicates concurrent requests to the same URL. Supports forced fetches that bypass the cache.
 * @param {string} url - The URL to fetch the HTML document from.
 * @param {boolean} [forceFetch=false] - If true, bypasses the cache and fetches fresh data.
 * @returns {Promise<Document>} The parsed HTML Document.
 * @throws {Error} If the network request fails or returns a non-OK status.
 */
async function fetchHTMLDocument(url, forceFetch = false) {
  cleanupCache();
  if (!forceFetch && ongoingRequests.has(url)) {return ongoingRequests.get(url);}
  try {
    if (!forceFetch) {
      const cachedDocument = cache.get(url), now = Date.now();
      if (cachedDocument && (now - cachedDocument.timestamp < cacheDuration)) {return cachedDocument.doc;}
    }
    const { signal } = abortController, promise = (async () => {
      const response = await fetch(url, { signal });
      if (!response.ok) {throw new Error(`Network error: ${response.status} ${response.statusText}`);}
      const htmlString = await response.text(), doc = parser.parseFromString(htmlString, "text/html");
      cache.set(url, { doc, timestamp: Date.now() });
      return doc;
    })();
    ongoingRequests.set(url, promise);
    const result = await promise;
    ongoingRequests.delete(url);
    return result;
  } catch (error) {
    if (debugging && error.name !== "AbortError") {console.error(error);}
    throw error;
  } finally {abortController = new AbortController();}
}

/**
 * @type {Set<string>}
 * @description Set of cache keys that have expired and need removal.
 */
const staleCacheKeys = new Set();

/**
 * @function cleanupCache
 * @description Identifies and removes expired cache entries older than cacheDuration.
 * @returns {void}
 */
function cleanupCache() {
  const now = Date.now();
  for (const [key, value] of cache.entries()) {
    if (now - value.timestamp >= cacheDuration) {staleCacheKeys.add(key);}
  }
  removeStaleCacheKeys();
}

/**
 * @function removeStaleCacheKeys
 * @description Removes all keys tracked in staleCacheKeys from the main cache, then clears the set.
 * @returns {void}
 */
function removeStaleCacheKeys() {
  for (const key of staleCacheKeys) {cache.delete(key);}
  staleCacheKeys.clear();
}

/**
 * @async
 * @function doRefresh
 * @description Main refresh entry point. Fetches the AJAX HTML document for the given URL,
 * refreshes the torrent display, reinitializes handlers, and updates the filter badge.
 * @param {Object} [options={}] - Refresh options.
 * @param {string} [options.url] - The URL to fetch; defaults to the current AJAX URL.
 * @param {boolean} [options.forceFetch=false] - Whether to bypass the cache.
 * @returns {Promise<void>}
 */
async function doRefresh({ url = window.location.href, forceFetch = false } = {}) {
  const defaultUrl = await getURL();
  const responseDoc = await fetchHTMLDocument(url || defaultUrl, forceFetch);
  await requestAnimationFramePromise(async () => await refreshTorrents(responseDoc));
  await initHandlers();
  await showBadge();
}

/**
 * @async
 * @function refreshTorrents
 * @description Core refresh function that updates the I2PSnark UI. Detects the current view
 * (torrent list, file directory, or offline state) and delegates to the appropriate update
 * function. Handles iframe detection, initialization delays, and volatile row updates.
 * @param {Function} [callback] - Optional callback to execute after refresh completes.
 * @returns {Promise<void>}
 */
async function refreshTorrents(callback) {
  try {
    const complete = document.getElementsByClassName("completed");
    const control = document.getElementById("torrentInfoControl");
    const dirlist = document.getElementById("dirlist");
    const down = document.getElementById("NotFound") || document.getElementById("down");
    const filterEnabled = localStorage.hasOwnProperty("snarkFilter");

    if (!initialized && !down) {
      initialized = true;
      if (window.top !== parent.window.top && !document.documentElement.classList.contains("iframed")) {
        document.documentElement.classList.add("iframed");
      }
      if (!document.getElementById("tnlInCount")) {
        snarkHead.classList.add("initializing");
        await new Promise(resolve => setTimeout(() => {
          snarkHead.classList.remove("initializing");
          resolve();
        }, 3 * 1000));
      }
    }

    if (!storageRefresh) {
      localStorage.setItem("snarkRefresh", await getRefreshInterval());
    }

    await setLinks(query);

    if (torrents) { await requestAnimationFramePromise(async () => await updateVolatile()); }
    else if (dirlist) {await requestAnimationFramePromise(async () => await updateFiles()); }
    else if (down) { await requestAnimationFramePromise(async () => await refreshAll()); }

    /**
     * @async
     * @function refreshAll
     * @description Performs a full refresh of the torrent table by fetching the complete
     * AJAX document and replacing the tbody content. Also refreshes the header/footer and screen log.
     * @returns {Promise<void>}
     */
    async function refreshAll() {
      try {
        const url = await getURL();
        const mainsectionContainer = await fetchHTMLDocument(url);
        const newTorrentsBody = mainsectionContainer.querySelector("#snarkTbody");
        if (newTorrentsBody) {
          await requestAnimationFramePromise(async () => {
            updateElement(torrentsBody, newTorrentsBody);
            refreshHeaderAndFooter();
            refreshScreenLog(undefined);
            if (noTorrents) {noTorrents.remove();}
            if (debugging) {console.log("refreshAll()");}
          });
        } else {
          const newMainsection = mainsectionContainer.querySelector("#mainsection");
          if (newMainSection) {await updateElement(mainsection, newMainsection);}
        }
      } catch (error) {
        if (debugging) console.error(error);
      }
    }

    /**
     * @async
     * @function updateVolatile
     * @description Performs an incremental update of torrent table rows. Compares each cell's
     * text content with the response and updates only changed cells. Falls back to a full
     * refresh if the number of rows differs or requireFullRefresh is set.
     * @returns {Promise<void>}
     */
    async function updateVolatile() {
      let updated = false;
      try {
        const url = await getURL();
        const responseDoc = await fetchHTMLDocument(url);

        const updating = torrents.querySelectorAll("#snarkTbody tr, #dhtDebug .dht");
        const updatingResponse = [...responseDoc.querySelectorAll("#snarkTbody tr, #dhtDebug .dht")];

        if (torrents) {
          if (noTorrents) {noTorrents.remove();}
          if (filterbar) {
            const activeBadge = filterbar.querySelector("#filterBar .filter#all .badge"),
                  activeBadgeResponse = responseDoc.querySelector("#filterBar .filter#all.enabled .badge");
            await updateElement(activeBadge, activeBadgeResponse, "textContent");

            const pagenavtop = document.getElementById("pagenavtop"),
                  pagenavtopResponse = responseDoc.querySelector("#pagenavtop"),
                  filterbarResponse = responseDoc.querySelector("#filterBar");

            if ((filterbar && !filterbarResponse) || (!pagenavtop && pagenavtopResponse)) {
              const torrentFormResponse = responseDoc.querySelector("#torrentlist");
              if (torrentFormResponse) {
                await updateElement(torrentForm, torrentFormResponse);
              }
              await initHandlers();
            } else if (pagenavtop && pagenavtopResponse && pagenavtop.outerHTML.trim() !== pagenavtopResponse.outerHTML.trim()) {
              pagenavtop.outerHTML = pagenavtopResponse.outerHTML;
              requireFullRefresh = true;
            }
          }

          if (updatingResponse.length === updating.length && !requireFullRefresh) {
            updating.forEach(async (currentRow, rowIndex) => {
              const currentRowTds = currentRow.querySelectorAll("td");
              const responseRowTds = updatingResponse[rowIndex].querySelectorAll("td");
              if (currentRowTds.length === responseRowTds.length) {
                currentRowTds.forEach((currentTd, tdIndex) => {
                  currentTd.textContent = responseRowTds[tdIndex].textContent;
                });

                if (currentRow.classList.toString() !== updatingResponse[rowIndex].classList.toString()) {
                  currentRow.classList = updatingResponse[rowIndex].classList;
                }
              }
            });
          } else if (requireFullRefresh && updatingResponse) {
            refreshAll();
            updated = true;
          }
        }
      } catch (error) {
        if (debugging) console.error(error);
      }
    }

    /**
     * @async
     * @function updateFiles
     * @description Updates file listing information by comparing and refreshing
     * incomplete file cells and torrent info stats from the fetched document.
     * @returns {Promise<void>}
     */
    async function updateFiles() {
      try {
        const url = window.location.href;
        const responseDoc = await fetchHTMLDocument(url);
        const selectors = ["#dirInfo tbody tr.incomplete td", "#torrentInfoStats .nowrap"];
        for (const selector of selectors) {
          const elements = document.querySelectorAll(selector);
          const responseElements = responseDoc.querySelectorAll(selector);
          if (responseElements.length === elements.length) {
            for (let index = 0; index < elements.length; index++) {
              const element = elements[index];
              const responseElement = responseElements[index];
              if (responseElement) {updateElement(element, responseElement);}
            }
          }
        }
      } catch (error) { if (debugging) console.error(error); }
    }

    /**
     * @async
     * @function refreshHeaderAndFooter
     * @description Refreshes the header and footer table header cells from the fetched document.
     * Also adjusts sort icon and option box visibility based on the number of torrent rows.
     * @returns {Promise<void>}
     */
    async function refreshHeaderAndFooter() {
      try {
        const url = await getURL();
        const responseDoc = await fetchHTMLDocument(url);
        const snarkFooter = document.getElementById("snarkFoot");
        const snarkFooterResponse = responseDoc.querySelector("#snarkFoot");
        const snarkHeader = document.getElementById("snarkHead");
        const snarkHeaderResponse = responseDoc.querySelector("#snarkHead");

        if (snarkFooter && snarkFooterResponse) {
          const thElements = snarkFooter.querySelectorAll("th");
          const thElementsResponse = snarkFooterResponse.querySelectorAll("th");

          if (thElements.length === thElementsResponse.length) {
            thElements.forEach((th, index) => {
              th.innerHTML = thElementsResponse[index].innerHTML;
            });
          }
        }

        if (snarkHeader && snarkHeaderResponse) {
          const thElements = snarkHeader.querySelectorAll("th");
          const thElementsResponse = snarkHeaderResponse.querySelectorAll("th");

          if (thElements.length === thElementsResponse.length) {
            thElements.forEach((th, index) => {
              th.innerHTML = thElementsResponse[index].innerHTML;
            });
          }
          const noload = torrents?.querySelector("#noTorrents");
          if ((torrentsBody && torrentsBody.children.length === 0) || noload) {
            snarkHeader.querySelectorAll("th:nth-child(n+2) .sortIcon, th:nth-child(n+2) .optbox")
                       .forEach(el => el.style.opacity = "0");
          } else if (torrentsBody && torrentsBody.children.length < 2) {
            snarkHeader.querySelectorAll("th:nth-child(n+2) .sortIcon")
                       .forEach(el => el.style.opacity = "0");
          } else {
            snarkHeader.querySelectorAll("th:nth-child(n+2):not(.tAction) img, th:nth-child(n+2):not(.tAction) input")
                       .forEach(el => el.style.opacity = "");
          }
        }
      } catch (error) {}
    }

  } catch (error) {}
}

/**
 * @async
 * @function refreshScreenLog
 * @description Fetches and updates the screen log (#messages) element. Uses a cache with
 * triple the normal duration. Optionally executes a callback after the update and supports
 * forced fetches to bypass the cache.
 * @param {Function} [callback] - Optional callback to execute after the screen log is updated.
 * @param {boolean} [forceFetch=false] - Whether to bypass the cache and fetch fresh data.
 * @returns {Promise<void>}
 */
async function refreshScreenLog(callback, forceFetch = false) {
  return new Promise(async (resolve) => {
    try {
      const screenlog = document.getElementById("messages");
      if (!screenlog || (screenlog.hidden && screenlog.textContent.trim() === "")) {
        resolve();
        return;
      }
      screenlog.removeAttribute("hidden");
      let responseDoc;
      if (!callback && !forceFetch && cache.has("screenlog")) {
        const [doc, expiry] = cache.get("screenlog");
        if (expiry > Date.now()) {responseDoc = doc;}
        else {cache.delete("screenlog");}
      }
      if (!responseDoc || forceFetch) {
        responseDoc = await fetchHTMLDocument("/i2psnark/.ajax/xhrscreenlog.html", forceFetch);
        if (!responseDoc) {
          resolve();
          return;
        }
        cache.set("screenlog", [responseDoc, Date.now() + cacheDuration * 3]);
      }
      const screenlogResponse = responseDoc.getElementById("messages");
      if (!screenlogResponse) {
        resolve();
        return;
      }
      await updateElement(screenlog, screenlogResponse);
      convertEncodedSpaces();
      if (callback) {callback();}
      resolve();
      convertEncodedSpaces();
    } catch (error) {resolve();}
  });
}

/**
 * @function convertEncodedSpaces
 * @description Replaces URL-encoded spaces (%20) with regular spaces in screen log message
 * text nodes for cleaner display.
 * @returns {void}
 */
function convertEncodedSpaces() {
  if (!screenlog) {return;}

  /**
   * @function replaceEncodedSpaces
   * @description Recursively traverses DOM nodes, replacing %20 with spaces in text nodes
   * and within anchor element children.
   * @param {Node} node - The DOM node to process.
   * @returns {void}
   */
  function replaceEncodedSpaces(node) {
    if (node.nodeType === Node.TEXT_NODE) {
      node.nodeValue = node.nodeValue.replace(/%20/g, " ");
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      if (node.tagName.toLowerCase() === "a") {
        Array.from(node.childNodes).forEach(replaceEncodedSpaces);
      } else {
        Array.from(node.childNodes).forEach(replaceEncodedSpaces);
      }
    }
  }
  const msgElements = screenlog.querySelectorAll("li.msg");
  if (msgElements) { msgElements.forEach(element => replaceEncodedSpaces(element)); }
}

/**
 * @function refreshOnSubmit
 * @description Attaches form submission handlers that refresh the screen log and torrent
 * display after forms are submitted via the hidden iframe. Also handles click delegation
 * for submit buttons, stop/start all actions, and the navigation refresh link.
 * @returns {void}
 */
function refreshOnSubmit() {
  const forms = document.querySelectorAll("form");
  forms.forEach((form) => {
    const iframe = document.getElementById("processForm");
    if (form && iframe) {
      const formSubmitted = new Promise((resolve) => {
        const loadHandler = () => {
          iframe.removeEventListener("load", loadHandler);
          resolve();
        };
        iframe.addEventListener("load", loadHandler);
      });

      form.onsubmit = async (event) => {
        const submitter = event.submitter;
        if (!(submitter instanceof HTMLInputElement && submitter.classList) && submitter !== null) {return;}
        await formSubmitted;
        await new Promise(resolve => setTimeout(resolve, 2000));
        await refreshScreenLog(undefined, true);
        convertEncodedSpaces();
      };
    }
  });

  document.addEventListener("click", (event) => {
    const clickTarget = event.target;
    const form = clickTarget.closest("form");
    const stopAllOrStartAllInactive = document.querySelector('input[id^="action"]:not(.depress)');
    const dirlist = document.getElementById("dirlist");
    if (clickTarget.matches("input[type=submit]")) {
      event.stopPropagation();
      clickTarget.form.requestSubmit();
      if (clickTarget.matches('input[id^="action"]')) {
        stopAllOrStartAllInactive.classList.add("tempDisabled");
        setTimeout(() => {stopAllOrStartAll.classList.remove("tempDisabled")}, 4000);
      }
    } else if (clickTarget.matches("#nav_main:not(.isConfig") && !dirlist) {
      const navMain = document.querySelector("#nav_main:not(.isConfig)");
      navMain.classList.add("isRefreshing");
      event.preventDefault();
      refreshScreenLog(refreshTorrents, true);
      setTimeout(() => {
        navMain.classList.remove("isRefreshing");
        clickTarget.blur();
      }, 200);
    }
  });
}

/**
 * @async
 * @function initSnarkRefresh
 * @description Initializes the I2PSnark refresh system. Sets up periodic refresh intervals,
 * initializes the lightbox for image viewing, cleans up old event listeners, and preloads
 * the offline indicator image. Called on visibility change to visible state.
 * @returns {Promise<void>}
 */
async function initSnarkRefresh() {
  let serverOKIntervalId = setInterval(checkIfUp, 5000);
  clearInterval(snarkRefreshIntervalId);
  document.documentElement.removeAttribute("style");
  const loaded = torrentsBody?.querySelector(".rowEven");
  const noload = torrents?.querySelector("#noTorrents");
  if (loaded && noload) {noload.remove();}
  try {
    snarkRefreshIntervalId = setInterval(async () => {
      try {
        if (isDocumentVisible) {
          await doRefresh();
          await showBadge();
          await refreshScreenLog();
          convertEncodedSpaces();
        }
      } catch (error) {
        if (debugging) console.error(error);
      }
    }, await getRefreshInterval());

    if (files && document.getElementById("lightbox")) {
      const lightbox = new Lightbox();
      lightbox.load();
    }

    const events = document._events?.click || [];
    events.forEach(event => document.removeEventListener("click", event));
    refreshOnSubmit();
  } catch (error) {
    if (debugging) console.error(error);
  }

  if (!chimpIsCached) {
    if (isStandalone) {preloadImage("/i2psnark/.res/themes/snark/midnight/images/chimp.webp");}
    else {preloadImage("/themes/snark/midnight/images/chimp.webp");}
    chimpIsCached = true;
  }
}

/**
 * @function stopSnarkRefresh
 * @description Clears the main torrent refresh interval. Called when the document becomes hidden.
 * @returns {void}
 */
function stopSnarkRefresh() {clearInterval(snarkRefreshIntervalId);}

/**
 * @async
 * @function checkIfUp
 * @description Periodically checks server connectivity by performing a HEAD request. Removes
 * the offline overlay if the server responds OK, or shows the offline screen if the request fails.
 * Respects a minimum delay between checks to avoid excessive requests.
 * @param {number} [minDelay=14000] - Minimum delay in milliseconds between connectivity checks.
 * @returns {Promise<void>}
 */
async function checkIfUp(minDelay = 14000) {
  const currentTime = Date.now();
  if (currentTime - lastCheckTime < minDelay) {return;}
  lastCheckTime = currentTime;

  if (!isDocumentVisible) {return;}
  try {
    const overlay = document.getElementById("offline");
    const offlineStylesheet = document.getElementById("offlineCss");
    const response = await fetch(window.location.href, { method: "HEAD" });
    if (response.ok) {
      if (isIframed) {parentDoc.documentElement.classList.remove("isDown");}
      if (overlay) {overlay.remove();}
      if (offlineStylesheet) {offlineStylesheet.remove();}
    }
  } catch (error) {
    if (debugging) {console.error(error);}
    if (isIframed) {parentDoc.documentElement.classList.add("isDown");}
    setTimeout(isDown, 3000);
    await refreshTorrents();
  }
}

/**
 * @function preloadImage
 * @description Preloads an image into the browser cache using the Cache API. Falls back to
 * direct loading if the Cache API is unavailable. Periodically re-caches the image to
 * prevent cache expiration.
 * @param {string} src - The image source URL to preload and cache.
 * @returns {void}
 */
function preloadImage(src) {
  const CACHE_NAME = "my-image-cache";
  const INTERVAL_MS = 10 * 60 * 1000;

  const show = (url) => {};

  const loadImage = (url) => show(url);

  /**
   * @async
   * @function tryFromCache
   * @description Attempts to load the image from the browser cache via the Cache API.
   * @param {string} u - The URL of the image to retrieve from cache.
   * @returns {Promise<boolean>} True if the image was found in cache and displayed, false otherwise.
   */
  async function tryFromCache(u) {
    if (!("caches" in window)) return false;
    try {
      const cache = await caches.open(CACHE_NAME);
      const res = await cache.match(new Request(u, { mode: "cors" }));
      if (res) {
        show(URL.createObjectURL(await res.blob()));
        return true;
      }
    } catch {}
    return false;
  }

  /**
   * @async
   * @function ensureCached
   * @description Ensures the image is cached in the browser. First checks the cache, then
   * fetches and caches the image if not found. Falls back to direct loading on failure.
   * @param {string} u - The URL of the image to ensure is cached.
   * @returns {Promise<void>}
   */
  async function ensureCached(u) {
    if (await tryFromCache(u)) return;
    if (!("caches" in window)) return loadImage(u);

    try {
      const req = new Request(u, { mode: "cors" });
      const res = await fetch(req, { cache: "reload" });
      if (!res.ok) return loadImage(u);

      const cache = await caches.open(CACHE_NAME);
      await cache.put(req, res.clone());
      show(URL.createObjectURL(await res.blob()));
    } catch {
      loadImage(u);
    }
  }

  ensureCached(src);
  setInterval(() => ensureCached(src), INTERVAL_MS);
}

/**
 * @function isDown
 * @description Creates and displays an offline overlay with a loading spinner and chimp image.
 * Injects inline CSS styles for the overlay and adds the "isDown" class to the parent
 * document in iframe mode. Only creates the overlay if it doesn't already exist.
 * @returns {void}
 */
function isDown() {
  let chimpSrc;
  if (isStandalone) {chimpSrc = "/i2psnark/.res/themes/snark/midnight/images/chimp.webp";}
  else {chimpSrc = "/themes/snark/midnight/images/chimp.webp";}
  const offlineStyles = `:root{--chimp:url(${chimpSrc});--spinner:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 128 128'%3E%3Cg%3E%3Cpath d='M78.75 16.18V1.56a64.1 64.1 0 0 1 47.7 47.7H111.8a49.98 49.98 0 0 0-33.07-33.08zM16.43 49.25H1.8a64.1 64.1 0 0 1 47.7-47.7V16.2a49.98 49.98 0 0 0-33.07 33.07zm33.07 62.32v14.62A64.1 64.1 0 0 1 1.8 78.5h14.63a49.98 49.98 0 0 0 33.07 33.07zm62.32-33.07h14.62a64.1 64.1 0 0 1-47.7 47.7v-14.63a49.98 49.98 0 0 0 33.08-33.07z' fill='%23dd5500bb'/%3E%3CanimateTransform attributeName='transform' type='rotate' from='0 64 64' to='-90 64 64' dur='1200ms' repeatCount='indefinite'/%3E%3C/g%3E%3C/svg%3E")}*{user-select:none}body,html,#circle,#offline{margin:0;padding:0;height:100vh;min-height:100%;position:relative;overflow:hidden}#circle,#offline{position:absolute;top:0;left:0;bottom:0;right:0}#offline{z-index:99999999;background:#000d;backdrop-filter:blur(2px)}#circle::before{content:"";width:230px;height:230px;border-radius:50%;border:28px solid #303;box-shadow:0 0 0 8px #000,0 0 0 8px #000 inset;display:block;position:absolute;top:calc(50% - 132px);left:calc(50% - 135px);background:radial-gradient(circle at center,rgba(0,0,0,0),70%,#313 75%),var(--spinner) no-repeat center center/240px,var(--chimp) no-repeat calc(50% + 5px) calc(50% + 10px)/250px,#000;transform:scale(.8);will-change:transform}`;
  const offlineCss = document.createElement("style");
  offlineCss.id = "offlineCss";
  offlineCss.textContent = offlineStyles;
  const offline = document.createElement("div");
  const spinner = document.createElement("div");
  offline.id = "offline";
  spinner.id = "circle";
  offline.appendChild(spinner);
  if (!document.getElementById("offline")) {
    document.head.appendChild(offlineCss);
    document.body.appendChild(offline);
  }
  if (isIframed) {parentDoc.documentElement.classList.add("isDown");}
}

document.addEventListener("visibilitychange", () => {
  isDocumentVisible = !document.hidden;
  if (isDocumentVisible) {initSnarkRefresh();}
  else {stopSnarkRefresh();}
});

document.addEventListener("DOMContentLoaded", () => {
  convertEncodedSpaces();
  document.body.removeAttribute("style");
});

export { doRefresh, getURL, initSnarkRefresh, refreshScreenLog, refreshTorrents, snarkRefreshIntervalId, isDocumentVisible };