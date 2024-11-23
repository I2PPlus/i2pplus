/* I2P+ I2PSnark refreshTorrents.js by dr|z3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {onVisible} from "./onVisible.js";
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

let snarkRefreshIntervalId;
let screenLogIntervalId;
let debugging = false;
let initialized = false;

async function requestIdleOrAnimationFrame(callback, timeout = 1000) {
  if (typeof requestIdleCallback === "function") {
    await new Promise(resolve => requestIdleCallback(() => {
      callback();
      resolve();
    }, { timeout }));
  } else {
    await new Promise(resolve => requestAnimationFrame(() => {
      callback();
      resolve();
    }));
  }
}

async function getRefreshInterval() {
  const refreshInterval = snarkRefreshDelay || parseInt(localStorage.getItem("snarkRefreshDelay")) || 5;
  localStorage.setItem("snarkRefresh", refreshInterval);
  return refreshInterval * 1000;
}

async function getURL() {
  return window.location.href.replace("/i2psnark/", "/i2psnark/.ajax/xhr1.html");
}

async function setLinks(query) {
  if (home) {home.href = query ? `/i2psnark/${query}` : "/i2psnark/";}
}

async function noAjax(delay) {
  const failMessage = "<div class=routerdown id=down><span>Router is down</span></div>";
  const targetElement = mainsection || document.getElementById("snarkInfo");
  await new Promise(resolve => setTimeout(() => {
    targetElement.innerHTML = failMessage;
    resolve();
  }, delay));
}

async function initHandlers() {
  await requestIdleOrAnimationFrame(async () => {
    await setLinks();
    if (screenlog) await initSnarkAlert();
    if (document.getElementById("pagenavtop")) await pageNav();
    if (torrents) await snarkSort();
    if (filterbar) await showBadge();
    if (debugMode) await toggleDebug();
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

async function refreshScreenLog(callback) {
  try {
    const screenlog = document.getElementById("messages");
    if (screenlog.hidden) return;

    let responseDoc;
    if (!callback && cache.has("screenlog")) {
      const [doc, expiry] = cache.get("screenlog");
      if (expiry > Date.now()) {
        responseDoc = doc;
      } else {
        cache.delete("screenlog");
      }
    }

    if (!responseDoc) {
      responseDoc = await fetchHTMLDocument("/i2psnark/.ajax/xhrscreenlog.html");
      cache.set("screenlog", [responseDoc, Date.now() + cacheDuration * 3]);
      setTimeout(() => cache.delete("screenlog"), cacheDuration * 3);
    }

    const notifyResponse = responseDoc.querySelector("#notify");
    const screenlogResponse = responseDoc.querySelector("#messages");
    await updateElementInnerHTML(screenlog, screenlogResponse);

    await new Promise(resolve => setTimeout(() => {
      const lowerSection = document.getElementById("lowersection");
      const [addNotify, createNotify] = [
        lowerSection.querySelector("#addNotify"),
        lowerSection.querySelector("#createNotify")
      ];
      updateElementInnerHTML(addNotify, notifyResponse);
      updateElementInnerHTML(createNotify, notifyResponse);
      resolve();
    }, 500));

    if (callback) callback();
  } catch {}
}

const parser = new DOMParser();
const container = document.createElement("div");
async function fetchHTMLDocument(url) {
  try {
    const cachedDocument = cache.get(url);
    if (cachedDocument && Date.now() - cachedDocument.timestamp < cacheDuration) {
      container.innerHTML = cachedDocument.html;
      return container;
    }
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error("Network error: No response from server");
    }
    const htmlString = await response.text();
    container.innerHTML = parser.parseFromString(htmlString, "text/html").body.innerHTML;
    cache.set(url, {html: container.innerHTML, timestamp: Date.now()});
    setTimeout(() => cache.delete(url), cacheDuration);
    return container;
  } catch (error) {
    if (debugging) {
      console.error(error);
      throw error;
    }
  }
}

async function doRefresh(url) {
  const responseDoc = await fetchHTMLDocument(url || (await getURL()));
  if (debugging) isXHRSynced(responseDoc);
  await requestIdleOrAnimationFrame(async () => await refreshTorrents(responseDoc));
  await initHandlers();
}

async function refreshTorrents(callback) {
  const complete = document.getElementsByClassName("completed");
  const control = document.getElementById("torrentInfoControl");
  const dirlist = document.getElementById("dirlist");
  const down = document.getElementById("NotFound");
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

  if (files || torrents) {
    await requestIdleOrAnimationFrame(async () => await updateVolatile());
  } else if (down) {
    await requestIdleOrAnimationFrame(async () => await refreshAll());
  }

  async function refreshAll() {
    try {
      const mainsectionContainer = await fetchHTMLDocument(await getURL());
      const newTorrents = mainsectionContainer.querySelector('#torrents');
      await updateElementInnerHTML(torrents, newTorrents);
      if (debugging) console.log("refreshAll()");
    } catch (error) {
      if (debugging) console.error(error);
    }
  }

  let updated = false, requireFullRefresh = true;

  async function updateVolatile() {
    const url = await getURL();
    const responseDoc = await fetchHTMLDocument(url);

    const updating = torrents.querySelectorAll("#snarkTbody tr, #dhtDebug .dht"),
          updatingResponse = Array.from(responseDoc.querySelectorAll("#snarkTbody tr, #dhtDebug .dht"));

    if (torrents) {
      if (filterbar) {
        const activeBadge = filterbar.querySelector("#torrentDisplay .filter#all .badge");
        const activeBadgeResponse = responseDoc.querySelector("#torrentDisplay .filter#all.enabled .badge");
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
        requireFullRefresh = false;
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
  }

  async function refreshHeaderAndFooter() {
    const url = await getURL();
    const responseDoc = await fetchHTMLDocument(url);
    const snarkFoot = document.getElementById("snarkFoot"),
          snarkFootResponse = responseDoc.querySelector("#snarkFoot");
    const snarkHeadResponse = responseDoc.querySelector("#snarkHead");
    await updateElementInnerHTML(snarkFoot, snarkFootResponse);
    await updateElementInnerHTML(snarkHead, snarkHeadResponse);
  }
}

async function initSnarkRefresh() {
  clearInterval(snarkRefreshIntervalId);
  onVisible(mainsection, async () => {
    const screenLogInterval = 5000;
    try {
      snarkRefreshIntervalId = setInterval(async () => {
        try {
          await doRefresh();
          await showBadge();
          await refreshScreenLog();
          await initToggleLog();
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
  });
}

export { doRefresh, getURL, initSnarkRefresh, refreshScreenLog, refreshTorrents, snarkRefreshIntervalId };