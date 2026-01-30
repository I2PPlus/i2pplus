/* I2P+ I2PSnark refreshTorrents.js by dr|z3d */
/* Selectively refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {MESSAGE_TYPES} from "./messageTypes.js";
import {pageNav} from "./pageNav.js";
import {showBadge} from "./filterBar.js";
import {snarkSort} from "./snarkSort.js";
import {toggleDebug} from "./toggleDebug.js";
import {Lightbox} from "./lightbox.js";
import {initSnarkAlert} from "./snarkAlert.js";

const cache = new Map(), cacheDuration = 5000;
const debugMode = document.getElementById("debugMode");
const files = document.getElementById("dirInfo");
const filterbar = document.getElementById("filterBar");
const home = document.querySelector("#navbar .nav_main");
const isIframed = document.documentElement.classList.contains("iframed") || window.parent;
const isStandalone = document.documentElement.classList.contains("standalone");
const mainsection = document.getElementById("mainsection");
const noTorrents = document.getElementById("noTorrents");
const parentDoc = window.parent.document;
const query = window.location.search;
const screenlog = document.getElementById("screenlog");
const snarkHead = document.getElementById("snarkHead");
const storageRefresh = localStorage.getItem("snarkRefresh");
const torrents = document.getElementById("torrents");
const torrentsBody = document.getElementById("snarkTbody");
const torrentForm = document.getElementById("torrentlist");

let chimpIsCached = false;
let noConnection = false;
let snarkRefreshIntervalId;
let screenLogIntervalId;
let debugging = false;
let initialized = false;
let isDocumentVisible = true;
let lastCheckTime = 0;
let requireFullRefresh = true;

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
  await requestAnimationFramePromise(async () => {
    if (document.getElementById("pagenavtop")) await pageNav();
    if (filterbar) await showBadge();
    if (debugging) console.log("initHandlers()");
  });
}

async function updateElement(elem, respElem, property = "innerHTML") {
  if (elem && respElem) {
    const currentContent = elem[property].trim();
    const newContent = respElem[property].trim();
    if (currentContent !== newContent) {elem[property] = newContent;}
  }
}

const worker = new Worker("/i2psnark/.res/js/snarkWork.js");
const parser = new DOMParser();
const container = document.createElement("div");
let abortController = new AbortController();
const ongoingRequests = new Map();

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

const staleCacheKeys = new Set();

function cleanupCache() {
  const now = Date.now();
  for (const [key, value] of cache.entries()) {
    if (now - value.timestamp >= cacheDuration) {staleCacheKeys.add(key);}
  }
  removeStaleCacheKeys();
}

function removeStaleCacheKeys() {
  for (const key of staleCacheKeys) {cache.delete(key);}
  staleCacheKeys.clear();
}

async function doRefresh({ url = window.location.href, forceFetch = false } = {}) {
  const defaultUrl = await getURL();
  const responseDoc = await fetchHTMLDocument(url || defaultUrl, forceFetch);
  await requestAnimationFramePromise(async () => await refreshTorrents(responseDoc));
  await initHandlers();
  await showBadge();
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

    if (torrents) { await requestAnimationFramePromise(async () => await updateVolatile()); }
    else if (dirlist) {await requestAnimationFramePromise(async () => await updateFiles()); }
    else if (down) { await requestAnimationFramePromise(async () => await refreshAll()); }

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

function convertEncodedSpaces() {
  if (!screenlog) {return;}
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

function stopSnarkRefresh() {clearInterval(snarkRefreshIntervalId);}

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

function preloadImage(src) {
  const CACHE_NAME = "my-image-cache";
  const INTERVAL_MS = 10 * 60 * 1000;

  const show = (url) => {};

  const loadImage = (url) => show(url);

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