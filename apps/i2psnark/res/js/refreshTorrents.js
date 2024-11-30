/* I2P+ I2PSnark refreshTorrents.js by dr|z3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {pageNav} from "./pageNav.js";
import {showBadge} from "./filterBar.js";
import {snarkSort} from "./snarkSort.js";
import {toggleDebug} from "./toggleDebug.js";
import {initToggleLog} from "./toggleLog.js";
import {Lightbox} from "./lightbox.js";
import {initSnarkAlert} from "./snarkAlert.js";

const cache = new Map(), cacheDuration = 5000;
const debugMode = document.getElementById("debugMode");
const files = document.getElementById("dirInfo");
const filterbar = document.getElementById("torrentDisplay");
const home = document.querySelector("#navbar .nav_main");
const mainsection = document.getElementById("mainsection");
const query = window.location.search;
const screenlog = document.getElementById("screenlog");
const snarkHead = document.getElementById("snarkHead");
const storageRefresh = localStorage.getItem("snarkRefresh");
const torrents = document.getElementById("torrents");
const torrentForm = document.getElementById("torrentlist");
const MESSAGE_TYPES = {
  FETCH_HTML_DOCUMENT: "FETCH_HTML_DOCUMENT",
  FETCH_HTML_DOCUMENT_RESPONSE: "FETCH_HTML_DOCUMENT_RESPONSE",
  CANCELLED: "CANCELLED",
  ERROR: "ERROR",
  ABORT: "ABORT",
  ABORTED: "ABORTED"
};

let noConnection = false;
let snarkRefreshIntervalId;
let screenLogIntervalId;
let debugging = false;
let initialized = false;
let isDocumentVisible = true;
let requireFullRefresh = true;

const requestIdleOrAnimationFrame = (callback, timeout = 180) => {
  let requestId, timeoutId;

  const execCallback = () => {
    try {callback();}
    catch (error) {
      if (debugging) console.error(error);
    } finally {
      clearTimeout(timeoutId);
      if (requestId) cancelIdleCallback(requestId);
    }
  };

  const scheduleExecution = () => {
    timeoutId = setTimeout(() => {
      if (requestId) cancelIdleCallback(requestId);
      requestAnimationFrame(execCallback);
    }, timeout);
  };

  if (typeof requestIdleCallback === "function") {
    requestId = requestIdleCallback(execCallback);
    scheduleExecution();
  } else {scheduleExecution();}

  const promise = new Promise(resolve => {
    const clear = () => {
      clearTimeout(timeoutId);
      if (requestId) cancelIdleCallback(requestId);
      resolve();
    };

    if (!requestIdleOrAnimationFrame.cancel) {requestIdleOrAnimationFrame.cancel = clear;}
    return clear;
  });

  return promise;
};

async function getRefreshInterval() {
  const refreshInterval = snarkRefreshDelay || parseInt(localStorage.getItem("snarkRefreshDelay")) || 5;
  localStorage.setItem("snarkRefresh", refreshInterval);
  return refreshInterval * 1000;
}

async function getURL() { return window.location.href.replace("/i2psnark/", "/i2psnark/.ajax/xhr1.html"); }

async function setLinks(query) { if (home) {home.href = query ? `/i2psnark/${query}` : "/i2psnark/";} }

async function initHandlers() {
  if (torrents) {
    snarkSort();
    if (debugMode) {toggleDebug();}
  }
  setLinks();
  await requestIdleOrAnimationFrame(async () => {
    if (document.getElementById("pagenavtop")) await pageNav();
    if (filterbar) await showBadge();
    if (debugging) console.log("initHandlers()");
  });
}

async function updateElementInnerHTML(elem, respElem) {
  if (elem && respElem && elem.innerHTML !== respElem.innerHTML) {
    elem.innerHTML = respElem.innerHTML;
  }
}

async function updateElementTextContent(elem, respElem) {
  if (elem && respElem && elem.textContent !== respElem.textContent) {
    elem.textContent = respElem.textContent;
  }
}

const worker = new Worker("/i2psnark/.res/js/snarkWork.js");
const parser = new DOMParser();
const container = document.createElement("div");
let abortController = new AbortController();

async function fetchHTMLDocument(url, forceFetch = false) {
  cleanupCache();
  try {
    if (!forceFetch) {
      const cachedDocument = cache.get(url);
      const now = Date.now();
      if (cachedDocument && (now - cachedDocument.timestamp < cacheDuration)) { return cachedDocument.doc; }
    }
    const { signal } = abortController;
    const response = await fetch(url, { signal });
    if (!response.ok) { throw new Error(`Network error: ${response.status} ${response.statusText}`); }
    const htmlString = await response.text();
    const doc = parser.parseFromString(htmlString, "text/html");
    cache.set(url, { doc, timestamp: Date.now() });
    return doc;
  } catch (error) {
    if (debugging) {
      if (error.name === "AbortError") { console.log("Fetch aborted"); }
      else { console.error(error); }
    }
    throw error;
  } finally { abortController = new AbortController(); }
}

function cleanupCache() {
  const now = Date.now();
  for (const [key, value] of cache.entries()) {
    if (now - value.timestamp >= cacheDuration) {cache.delete(key);}
  }
}

async function doRefresh({ url = window.location.href, forceFetch = false } = {}) {
  let defaultUrl;
  try {
    defaultUrl = await getURL();
    const responseDoc = await fetchHTMLDocument(url || defaultUrl, forceFetch);
    await requestIdleOrAnimationFrame(async () => await refreshTorrents(responseDoc));
    await initHandlers();
    showBadge;
  } catch (error) { resolve(); }
}

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

    if (files || torrents) { await requestIdleOrAnimationFrame(async () => await updateVolatile()); }
    else if (down) { await requestIdleOrAnimationFrame(async () => await refreshAll()); }

    async function refreshAll() {
      try {
        const url = await getURL();
        const mainsectionContainer = await fetchHTMLDocument(url);
        const newTorrents = mainsectionContainer.querySelector("#torrents");
        if (newTorrents) {await updateElementInnerHTML(torrents, newTorrents);}
        else {
          const newMainsection = mainsectionContainer.querySelector("#mainsection");
          await updateElementInnerHTML(mainsection, newMainsection);
        }
        if (debugging) {console.log("refreshAll()");}
      } catch (error) {
        if (debugging) console.error(error);
      }
    }

    async function updateVolatile() {
      let updated = false;
      try {
        const url = await getURL();
        const responseDoc = await fetchHTMLDocument(url);

        const updating = torrents.querySelectorAll("#snarkTbody tr, #dhtDebug .dht"),
              updatingResponse = Array.from(responseDoc.querySelectorAll("#snarkTbody tr, #dhtDebug .dht"));

        if (torrents) {
          if (filterbar) {
            const activeBadge = filterbar.querySelector("#torrentDisplay .filter#all .badge"),
                  activeBadgeResponse = responseDoc.querySelector("#torrentDisplay .filter#all.enabled .badge");
            await updateElementTextContent(activeBadge, activeBadgeResponse);

            const pagenavtop = document.getElementById("pagenavtop"),
                  pagenavtopResponse = responseDoc.querySelector("#pagenavtop"),
                  filterbarResponse = responseDoc.querySelector("#torrentDisplay");

            if ((!filterbar && filterbarResponse) || (!pagenavtop && pagenavtopResponse)) {
              const torrentFormResponse = responseDoc.querySelector("#torrentlist");
              await updateElementInnerHTML(torrentForm, torrentFormResponse);
              await initHandlers();
            } else if (pagenavtop && pagenavtopResponse && pagenavtop.outerHTML !== pagenavtopResponse.outerHTML) {
              pagenavtop.outerHTML = pagenavtopResponse.outerHTML;
              requireFullRefresh = true;
            }
          }

          if (updatingResponse.length === updating.length && !requireFullRefresh) {
            updating.forEach(async (item, i) => {
              await updateElementInnerHTML(item, updatingResponse[i]);
            });
          } else if (requireFullRefresh && updatingResponse) {
            await requestIdleOrAnimationFrame(async () => await refreshAll());
            updated = true;
          }
        } else if (dirlist?.responseDoc) {
          if (control) {
            const controlResponse = responseDoc.querySelector("#torrentInfoControl");
            await updateElementInnerHTML(control, controlResponse);
          }

          if (complete.length && dirlist?.responseDoc) {
            const completeResponse = Array.from(responseDoc.querySelectorAll(".completed"));
            for (let i = 0; i < complete.length && completeResponse.length; i++) {
              await updateElementInnerHTML(complete[i], completeResponse[i]);
            }
          }
        }
      } catch (error) {
        if (debugging) console.error(error);
      }
    }

    async function refreshHeaderAndFooter() {
      try {
        const url = await getURL();
        const responseDoc = await fetchHTMLDocument(url);
        const snarkFoot = document.getElementById("snarkFoot"),
              snarkFootResponse = responseDoc.querySelector("#snarkFoot");
        const snarkHeadResponse = responseDoc.querySelector("#snarkHead");
        await updateElementInnerHTML(snarkFoot, snarkFootResponse);
        await updateElementInnerHTML(snarkHead, snarkHeadResponse);
      } catch(error) {}
    }

  } catch (error) {}
}

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
      await updateElementInnerHTML(screenlog, screenlogResponse);
      if (callback) callback();
      resolve();
    } catch (error) {resolve();}
  });
}

function refreshOnSubmit() {
  document.addEventListener("click", function(event) {
    if (event.target.tagName === "INPUT" && event.target.type === "submit") {refreshScreenLog(null, true);}
  });
}

async function initSnarkRefresh() {
  let serverOKIntervalId = setInterval(checkIfUp, 5000);
  clearInterval(snarkRefreshIntervalId);
  try {
    snarkRefreshIntervalId = setInterval(async () => {
      try {
        if (isDocumentVisible) {
          await doRefresh();
          await showBadge();
          await refreshScreenLog();
          await initToggleLog();
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
}

function stopSnarkRefresh() {
  clearInterval(snarkRefreshIntervalId);
}

async function checkIfUp() {
  if (!isDocumentVisible) {return;}
  try {
    const overlay = document.getElementById("offline");
    const offlineStylesheet = document.getElementById("offlineCss");
    const response = await fetch(window.location.href, { method: "HEAD" });
    if (response.ok) {
      if (overlay) {overlay.remove();}
      if (offlineStylesheet) {offlineStylesheet.remove();}
    }
  } catch (error) {
    if (debugging) {console.error(error);}
    setTimeout(isDown, 3000);
    await refreshTorrents();
  }
}

function isDown() {
  const offlineStyles = `:root{--chimp:url(/themes/snark/midnight/images/chimp.webp);--spinner:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 128 128'%3E%3Cg%3E%3Cpath d='M78.75 16.18V1.56a64.1 64.1 0 0 1 47.7 47.7H111.8a49.98 49.98 0 0 0-33.07-33.08zM16.43 49.25H1.8a64.1 64.1 0 0 1 47.7-47.7V16.2a49.98 49.98 0 0 0-33.07 33.07zm33.07 62.32v14.62A64.1 64.1 0 0 1 1.8 78.5h14.63a49.98 49.98 0 0 0 33.07 33.07zm62.32-33.07h14.62a64.1 64.1 0 0 1-47.7 47.7v-14.63a49.98 49.98 0 0 0 33.08-33.07z' fill='%23dd5500bb'/%3E%3CanimateTransform attributeName='transform' type='rotate' from='0 64 64' to='-90 64 64' dur='1200ms' repeatCount='indefinite'/%3E%3C/g%3E%3C/svg%3E")}*{user-select:none}body,html,#circle,#offline{margin:0;padding:0;height:100vh;min-height:100%;position:relative;overflow:hidden}#circle,#offline{position:absolute;top:0;left:0;bottom:0;right:0}#offline{z-index:99999999;background:#000d;backdrop-filter:blur(2px)}#circle::before{content:"";width:230px;height:230px;border-radius:50%;border:28px solid #303;box-shadow:0 0 0 8px #000,0 0 0 8px #000 inset;display:block;position:absolute;top:calc(50% - 132px);left:calc(50% - 135px);background:radial-gradient(circle at center,rgba(0,0,0,0),70%,#313 75%),var(--spinner) no-repeat center center/240px,var(--chimp) no-repeat calc(50% + 5px) calc(50% + 10px)/250px,#000;transform:scale(.8);will-change:transform}`;
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
    torrents.querySelectorAll("td, th").forEach(cell => {cell.textContent = "";});
    screenlog.querySelectorAll(".msg").forEach(li => {li.textContent = "";});
  }
}

document.addEventListener("visibilitychange", () => {
  isDocumentVisible = !document.hidden;
  if (isDocumentVisible) {initSnarkRefresh();}
  else {stopSnarkRefresh();}
});

export { doRefresh, getURL, initSnarkRefresh, refreshScreenLog, refreshTorrents, snarkRefreshIntervalId, isDocumentVisible };