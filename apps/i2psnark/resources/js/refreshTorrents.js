/* I2PSnark refreshTorrents.js by dr|z3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {onVisible} from "./onVisible.js";
import {initFilterBar} from './torrentDisplay.js';
import {initLinkToggler, magnetToClipboard, attachMagnetListeners} from "./toggleLinks.js";
import {initToggleLog} from "./toggleLog.js";
import {Lightbox} from "./lightbox.js";

const files = document.getElementById("dirInfo");
const filterbar = document.getElementById("torrentDisplay");
const home = document.querySelector(".nav_main");
const mainsection = document.getElementById("mainsection");
const storageRefresh = window.localStorage.getItem("snarkRefresh");
const xhrsnark = new XMLHttpRequest();
let refreshIntervalId;
let refreshTimeoutId;
let requestInProgress = false;

function debounce(func, wait) {
  let timeout;

  return function executedFunction(...args) {
    const later = () => {
      clearTimeout(timeout);
      func(...args);
    };

    clearTimeout(timeout);

    if (arguments[2] && arguments[2] === "now") {
      func(...args);
    } else {
      timeout = setTimeout(later, wait);
    }
  };
}

function refreshTorrents(callback) {
  const complete = document.getElementsByClassName("completed");
  const control = document.getElementById("torrentInfoControl");
  const dirlist = document.getElementById("dirlist");
  const down = document.getElementById("down");
  const info = document.getElementById("torrentInfoStats");
  const noload = document.getElementById("noload");
  const notfound = document.getElementById("NotFound");
  const snarkHead = document.getElementById("snarkHead");
  const torrents = document.getElementById("torrents");
  const storage = window.localStorage.getItem("snarkFilter");
  const filterEnabled = localStorage.hasOwnProperty("snarkFilter") !== null;
  const snarkTable = torrents || files;
  const query = window.location.search;

  if (requestInProgress) {
    if (refreshTimeoutId) {
      clearTimeout(refreshTimeoutId);
    }
    refreshTimeoutId = setTimeout(() => refreshTorrents(callback), 1000);
    return;
  }

  if (document.getElementById("ourDest") === null) {
    const childElems = document.querySelectorAll("#snarkHead th:not(.torrentAction)>*");
    if (snarkHead !== null) {
      document.getElementById("snarkHead").classList.add("initializing");
      childElems.forEach(elem => {elem.style.opacity = "0";});
    }
  }

  requestInProgress = true;

  function getURL() {
    const filterEnabled = localStorage.getItem("snarkFilter") !== null;
    const baseUrl = "/i2psnark/.ajax/xhr1.html";
    const url = filterEnabled ? baseUrl + query + (query ? "&" : "?") + "ps=9999" : baseUrl + query;
    return url;
  }

  xhrsnark.open("GET", getURL(), true);
  xhrsnark.responseType = "document";
  xhrsnark.onload = function () {

    if (storageRefresh === null) {
      getRefreshInterval();
    }

    if (down || noload) {
      refreshAll();
    } else if (files || torrents) {
      refreshHeaderAndFooter();
      updateVolatile();
    }

    if (torrents?.responseXML) {
        filterbar.removeEventListener("mouseover", setLinks);
        const torrentsContainer = document.getElementById("torrents");
        if (torrentsContainer?.responseXML) {
          const newTorrents = torrents.responseXML.getElementById("torrents");
          if (newTorrents && torrentsContainer.innerHTML !== newTorrents.innerHTML) {
            torrentsContainer.innerHTML = newTorrents.innerHTML;
          }
        }
    } else if (dirlist?.responseXML) {
      if (info) {
        const infoResponse = xhrsnark.responseXML.getElementById("torrentInfoStats");
        if (infoResponse) {
          const infoParent = info.parentNode;
          if (!Object.is(info.innerHTML, infoResponse.innerHTML)) {
            infoParent.replaceChild(infoResponse, info);
          }
        }
      }
      if (control) {
        const controlResponse = xhrsnark.responseXML.getElementById("torrentInfoControl");
        if (controlResponse) {
          const controlParent = control.parentNode;
          if (!Object.is(control.innerHTML, controlResponse.innerHTML)) {
            controlParent.replaceChild(controlResponse, control);
          }
        }
      }
      if (complete?.length && dirlist?.responseXML) {
        const completeResponse = xhrsnark.responseXML.getElementsByClassName("completed");
        for (let i = 0; i < complete.length && completeResponse?.length; i++) {
          if (!Object.is(complete[i].innerHTML, completeResponse[i]?.innerHTML)) {
            complete[i].innerHTML = completeResponse[i].innerHTML;
          }
        }
      }
      setLinks(query);
    }

    function refreshHeaderAndFooter() {
      const snarkHead = document.getElementById("snarkHead");
      if (snarkHead?.responseXML) {
        const snarkHeadParent = snarkHead.parentNode;
        const snarkHeadResponse = snarkHead.responseXML?.getElementById("snarkHead");
        if (snarkHead && snarkHeadResponse && !Object.is(snarkHead.innerHTML, snarkHeadResponse.innerHTML)) {
          snarkHead.innerHTML = snarkHeadResponse.innerHTML;
        }
      }
    }

    function refreshAll() {
      const files = document.getElementById("dirInfo");
      const mainsection = document.getElementById("mainsection");
      const notfound = document.getElementById("NotFound");
      const dirlist = document.getElementById("dirlist");
      if (mainsection) {
        const mainsectionResponse = xhrsnark.responseXML?.getElementById("mainsection");
        if (mainsectionResponse && mainsection.innerHTML !== mainsectionResponse.innerHTML) {
          mainsection.innerHTML = mainsectionResponse.innerHTML;
        }
        if (filterbar) {
          initFilterBar();
        }
      } else if (files) {
        const dirlistResponse = xhrsnark.responseXML?.getElementById("dirlist");
        if (dirlistResponse && !Object.is(dirlist.innerHTML, dirlistResponse.innerHTML) && notfound === null) {
          dirlist.innerHTML = dirlistResponse.innerHTML;
        }
      }
    }

    function updateVolatile() {
      const dirlist = document.getElementById("dirlist");
      const updating = document.getElementsByClassName("volatile");
      const updatingResponse = xhrsnark.responseXML?.getElementsByClassName("volatile");
      const updatingTds = document.querySelectorAll(".volatile td:not(.details.data):not(.magnet):not(.trackerLink)");
      const updatingTdsResponse = xhrsnark.responseXML?.querySelectorAll(".volatile td:not(.details.data):not(.magnet):not(.trackerLink)");
      if (updatingResponse?.length && updating.length === updatingResponse.length) {
        let updated = false;
        Array.from(updatingTds).forEach((elem, index) => {
          if (elem.innerHTML !== updatingTdsResponse[index].innerHTML) {
            elem.innerHTML = updatingTdsResponse[index].innerHTML;
            updated = true;
          }
        });
        if (updated && filterbar) {
          initFilterBar();
        }
      } else {
        refreshAll();
      }
    }

    function updateIfVisible() {
      const delay = getRefreshInterval();
      let snarkUpdateId = null;
      const onUpdate = () => {
        clearTimeout(snarkUpdateId);
        snarkUpdateId = setTimeout(onUpdate, delay);
      };
      onUpdate();
    }

    if (typeof callback === "function") {
      callback(xhrsnark.responseXML, getURL());
    }

    //updateIfVisible();

    function getRefreshInterval() {
      const interval = parseInt(xhrsnark.getResponseHeader("X-Snark-Refresh-Interval")) || 5;
      localStorage.setItem("snarkRefresh", interval);
      return interval * 1000;
    }
    requestInProgress = false;
  };

  xhrsnark.onerror = function (error) {
    console.error("XHR request failed:", error);
    noAjax(5000);
    if (refreshTimeoutId) {
      clearTimeout(refreshTimeoutId);
    }
    refreshTimeoutId = setTimeout(() => refreshTorrents(callback), 1000);
    requestInProgress = false;
  };
  xhrsnark.send();
}

function setLinks(query) {
  const home = document.querySelector('.nav_main');
  if (home) {
    if (query !== undefined && query !== null) {
      home.href = `/i2psnark/${query}`;
    } else {
      home.href = "/i2psnark/";
    }
  }
}

function noAjax(delay) {
  var failMessage = "<div class='routerdown' id='down'><span>Router is down</span></div>";
  var targetElement = mainsection || snarkInfo;
  setTimeout(function() {
    if (targetElement) {
      targetElement.innerHTML = failMessage;
    }
  }, delay);
  refreshAll();
}

function debouncedRefreshTorrents(callback) {
  const delay = 100;
  let debounceTimeoutId;

  return function executedFunction(...args) {
    const later = () => {
      clearTimeout(debounceTimeoutId);
      callback(refreshTorrents(...args));
    };

    clearTimeout(debounceTimeoutId);

    debounceTimeoutId = setTimeout(later, delay);
  };
}

function setupPage() {
  if (mainsection) {
    initToggleLog();
    initLinkToggler();
  }
  if (filterbar) {
    initFilterBar();
  }
}

function initSnarkRefresh() {
  const interval = (parseInt(storageRefresh) || 5) * 1000;
  clearInterval(refreshIntervalId);
  refreshIntervalId = setInterval(() => {
    debouncedRefreshTorrents(refreshTorrents)(setupPage);
  }, interval);
  if (files && lightboxIsActive()) {
    var lightbox = new Lightbox();lightbox.load();
  }
}

export {initSnarkRefresh, refreshTorrents, debouncedRefreshTorrents, debounce, xhrsnark, refreshIntervalId};