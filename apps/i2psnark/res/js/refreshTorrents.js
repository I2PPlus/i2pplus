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
const xhrsnark = new XMLHttpRequest();

let snarkRefreshIntervalId;
let screenLogIntervalId;
let snarkRefreshTimeoutId;
let debugging = false;
let initialized = false;

function refreshTorrents(callback) {
  const complete = document.getElementsByClassName("completed");
  const control = document.getElementById("torrentInfoControl");
  const dirlist = document.getElementById("dirlist");
  const down = document.getElementById("NotFound");
  const filterEnabled = localStorage.hasOwnProperty("snarkFilter");

  if (!initialized && !down) {
    initialized = true;
    if (!document.getElementById("ourDest")) {
      const childElems = torrents.querySelectorAll("#snarkHead th:not(.torrentAction)>*");
      snarkHead.classList.add("initializing");
      //childElems.forEach(elem => elem.style.opacity = "0");
      setTimeout(() => {
        snarkHead.classList.remove("initializing");
        //childElems.forEach(elem => elem.style.opacity = "");
      }, 3*1000);
    }
  }

  if (!storageRefresh) {localStorage.setItem("snarkRefresh", getRefreshInterval());}

  setLinks(query);

  if (files || torrents) {requestAnimationFrame(updateVolatile);}
  else if (down) {requestAnimationFrame(refreshAll);}

  async function refreshAll() {
    try {
      const mainsectionResponse = xhrsnark.responseXML?.getElementById("mainsection");
      if (mainsection && mainsectionResponse && mainsection.innerHTML !== mainsectionResponse.innerHTML) {
        const torrentsResponse = xhrsnark.responseXML.getElementById("torrents");
        if (torrents) {
          requestAnimationFrame(() => torrents.innerHTML = torrentsResponse.innerHTML);
        } else {
          requestAnimationFrame(() => mainsection.innerHTML = mainsectionResponse.innerHTML);
        }
      } else if (files) {
        const dirlistResponse = xhrsnark.responseXML?.getElementById("dirlist");
        if (dirlistResponse && dirlist.innerHTML !== dirlistResponse.innerHTML && !down) {
          dirlist.innerHTML = dirlistResponse.innerHTML;
        }
      }
      if (debugging) console.log("refreshAll()");
    } catch (error) { if (debugging) console.error(error); }
  }

  let updated = false;
  let requireFullRefresh = true;

  function updateVolatile() {
    if (!xhrsnark.responseXML) return;

    const updating = torrents.querySelectorAll("#snarkTbody tr, #dhtDebug .dht");
    const updatingResponse = xhrsnark.responseXML.querySelectorAll("#snarkTbody tr, #dhtDebug .dht");

    const updates = []; // Store updates to apply later

    if (torrents) {

      if (filterbar) {
        const activeBadge = filterbar.querySelector(".filter .badge:not(:empty)");
        const activeBadgeResponse = xhrsnark.responseXML.querySelector("#torrentDisplay .filter.enabled .badge:not(:empty)");
        if (activeBadge && activeBadgeResponse && activeBadge.textContent !== activeBadgeResponse.textContent) {
          updates.push(() => activeBadge.textContent = activeBadgeResponse.textContent);
        }

        const pagenavtop = document.getElementById("pagenavtop");
        const pagenavtopResponse = xhrsnark.responseXML.getElementById("pagenavtop");
        const filterbarResponse = xhrsnark.responseXML.getElementById("torrentDisplay");

        if ((!filterbar && filterbarResponse) || (!pagenavtop && pagenavtopResponse)) {
          const torrentForm = document.getElementById("torrentlist");
          const torrentFormResponse = xhrsnark.responseXML.getElementById("torrentlist");
          updates.push(() => torrentForm.innerHTML = torrentFormResponse.innerHTML);
          initHandlers();
        } else if (pagenavtop && pagenavtopResponse && pagenavtop.outerHTML !== pagenavtopResponse.outerHTML) {
          updates.push(() => pagenavtop.outerHTML = pagenavtopResponse.outerHTML);
          requireFullRefresh = true;
        }
      }

      if (updatingResponse.length === updating.length && !requireFullRefresh) {
        updating.forEach((item, i) => {
          if (item.innerHTML !== updatingResponse[i].innerHTML) {
            updates.push(() => item.outerHTML = updatingResponse[i].outerHTML);
          }
        });
      } else if (requireFullRefresh && updatingResponse) {
        requestAnimationFrame(refreshAll);
        updated = true;
        requireFullRefresh = false;
      }
    } else if (dirlist?.responseXML) {
      if (control) {
        const controlResponse = xhrsnark.responseXML.getElementById("torrentInfoControl");
        if (controlResponse && control.innerHTML !== controlResponse.innerHTML) {
          updates.push(() => {control.replaceWith(controlResponse);});
        }
      }
      if (complete.length && dirlist?.responseXML) {
        const completeResponse = xhrsnark.responseXML.getElementsByClassName("completed");

        for (let i = 0; i < complete.length && completeResponse.length; i++) {
          if (complete[i].innerHTML !== completeResponse[i].innerHTML) {
            updates.push(() => complete[i].innerHTML = completeResponse[i].innerHTML);
          }
        }
      }
    }

    if (updates.length > 0) {
      const fragment = document.createDocumentFragment();
      updates.forEach(update => {update(fragment);});
      requestAnimationFrame(() => {document.body.appendChild(fragment);});
    }

  }

  function refreshHeaderAndFooter() {
    if (!xhrsnark.responseXML) return;

    const snarkFoot = document.getElementById("snarkFoot");
    const snarkFootResponse = xhrsnark.responseXML.getElementById("snarkFoot");
    const snarkHeadResponse = xhrsnark.responseXML.getElementById("snarkHead");

    if (snarkHead && snarkHeadResponse && snarkHead.innerHTML !== snarkHeadResponse.innerHTML) {
      snarkHead.innerHTML = snarkHeadResponse.innerHTML;
      snarkSort();
    }
    if (snarkFoot && snarkFootResponse && snarkFoot.innerHTML !== snarkFootResponse.innerHTML) {
      snarkFoot.innerHTML = snarkFootResponse.innerHTML;
    }
  }

  xhrsnark.onerror = error => {
    noAjax(5000);
    clearTimeout(snarkRefreshTimeoutId);
    snarkRefreshTimeoutId = setTimeout(() => refreshTorrents(initHandlers), 1000);
  };
}

function getRefreshInterval() {
  const refreshInterval = parseInt(localStorage.getItem("snarkRefreshDelay")) || 5;
  localStorage.setItem("snarkRefresh", refreshInterval);
  return refreshInterval * 1000;
}

function refreshScreenLog(callback) {
  const screenlog = document.getElementById("messages");
  if (!screenlog || screenlog.hidden) return;

  const xhrsnarklog = new XMLHttpRequest();
  const lowerSection = document.getElementById("lowersection");
  const addNotify = lowerSection.querySelector("#addNotify");
  const createNotify = lowerSection.querySelector("#createNotify");

  xhrsnarklog.open("GET", "/i2psnark/.ajax/xhrscreenlog.html");
  xhrsnarklog.responseType = "document";
  xhrsnarklog.onload = () => {
    const notifyResponse = xhrsnarklog.responseXML.getElementById("notify");
    const screenlogResponse = xhrsnarklog.responseXML.getElementById("messages");
    if (screenlog && screenlogResponse && screenlog.innerHTML !== screenlogResponse.innerHTML) {
      screenlog.innerHTML = screenlogResponse.innerHTML;
    }
    if (xhrsnarklog.readyState === 4 && xhrsnarklog.status === 200) {
      setTimeout(() => {
        if (addNotify.innerHTML !== notifyResponse.innerHTML) {
          addNotify.innerHTML = notifyResponse.innerHTML;
        }
        if (createNotify.innerHTML !== notifyResponse.innerHTML) {
          createNotify.innerHTML = notifyResponse.innerHTML;
        }
      }, 500);
      if (callback) callback();
    }
  };
  xhrsnarklog.send();
  if (debugging) console.log("Updated screenlog");
}

function getURL() { return window.location.href.replace("/i2psnark/", "/i2psnark/.ajax/xhr1.html"); }

function initHandlers() {
  requestAnimationFrame(() => {
    setLinks();
    if (screenlog) initSnarkAlert();
    if (document.getElementById("pagenavtop")) pageNav();
    if (filterbar) showBadge();
    if (torrents) snarkSort();
    if (debugMode) toggleDebug();
    if (debugging) console.log("initHandlers()");
  });
}

function setLinks(query) {
  if (home) { home.href = query ? `/i2psnark/${query}` : "/i2psnark/"; }
}

function noAjax(delay) {
  const failMessage = "<div class=routerdown id=down><span>Router is down</span></div>";
  const targetElement = mainsection || document.getElementById("snarkInfo");
  setTimeout(() => targetElement.innerHTML = failMessage, delay);
}

async function initSnarkRefresh() {
  clearInterval(snarkRefreshIntervalId);
  onVisible(mainsection, () => {
    const screenLogInterval = 3000;
    try {
      snarkRefreshIntervalId = setInterval(async () => {
        try {
          await doRefresh();
          await refreshScreenLog();
          await initToggleLog();
        } catch (error) {if (debugging) console.error(error);}
      }, getRefreshInterval());

      if (files && document.getElementById("lightbox")) {
        const lightbox = new Lightbox();
        lightbox.load();
      }

      const events = document._events?.click || [];
      events.forEach(event => document.removeEventListener("click", event));
      refreshOnSubmit();
    } catch (error) {if (debugging) console.error(error);}
  });
}

function refreshOnSubmit() {
  document.addEventListener("click", event => {
    if (event.target.tagName === "INPUT" && event.target.type === "submit") {refreshScreenLog();}
  });
}

const REQUEST_TIMEOUT = 5000;
function doRefresh(url, callback) {
  xhrsnark.open("GET", url || getURL(), true);
  xhrsnark.timeout = REQUEST_TIMEOUT;
  xhrsnark.responseType = "document";
  xhrsnark.onload = () => {
    if (debugging) isXHRSynced();
    requestAnimationFrame(refreshTorrents);
    initHandlers();
  };
  xhrsnark.onerror = () => {
    if (xhrsnark.readyState === 4 && xhrsnark.status === 0) {
      setTimeout(() => doRefresh(url, callback), REQUEST_TIMEOUT);
    }
  };
  xhrsnark.send();
}

function isXHRSynced() {
  if (!debugging) return;
  const updating = torrents.querySelectorAll("#snarkTbody tr, #torrents #snarkFoot th");
  const updatingResponse = xhrsnark.responseXML.querySelectorAll("#snarkTbody tr, #torrents #snarkFoot th");
  console.log(`html elements: ${updating.length} / xhr elements: ${updatingResponse.length}`);
  if (updating.length !== updatingResponse.length) {
    updating.forEach((item, i) => {
      if (item && item.outerHTML !== updatingResponse[i].outerHTML) {
        console.log(`Missing element: Class: ${item.className}, ID: ${item.id}`);
      }
    });
  }
}

function countSnarks() {return torrents.querySelectorAll("tr.volatile").length;}

export { countSnarks, doRefresh, getURL, initSnarkRefresh, refreshScreenLog, refreshTorrents, snarkRefreshIntervalId, xhrsnark };