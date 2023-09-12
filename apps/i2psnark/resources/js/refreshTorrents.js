/* I2PSnark refreshTorrents.js by dr|3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {onVisible} from "./onVisible.js";
import {initFilterBar, checkFilterBar, refreshFilters, selectFilter, checkPagenav} from './torrentDisplay.js';
import {initLinkToggler} from "./toggleLinks.js";
import {initToggleLog} from "./toggleLog.js";

const home = document.querySelector(".nav_main");
const storageRefresh = window.localStorage.getItem("snarkRefresh");
let refreshIntervalId;

function debounce(func, wait) {
  let timeout;

  return function executedFunction(...args) {
    const later = () => {
      clearTimeout(timeout);
      func(...args);
    };

    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
}

function refreshTorrents(callback) {
  const complete = document.getElementsByClassName("completed");
  const control = document.getElementById("torrentInfoControl");
  const dirlist = document.getElementById("dirlist");
  const down = document.getElementById("down");
  const files = document.getElementById("dirInfo");
  const filterbar = document.getElementById("torrentDisplay");
  const info = document.getElementById("torrentInfoStats");
  const mainsection = document.getElementById("mainsection");
  const noload = document.getElementById("noload");
  const notfound = document.getElementById("NotFound");
  const thead = document.getElementById("snarkHead");
  const torrents = document.getElementById("torrents");
  const storage = window.localStorage.getItem("snarkfilter");
  const filterEnabled = localStorage.hasOwnProperty("snarkfilter");
  const snarkTable = torrents || files;
  const baseUrl = "/i2psnark/.ajax/xhr1.html";
  const query = window.location.search;
  const url = filterEnabled ? baseUrl + query + (query ? "&" : "?") + "ps=9999" : baseUrl;
  const xhrsnark = new XMLHttpRequest();

  xhrsnark.open("GET", url, true);
  xhrsnark.responseType = "document";
  xhrsnark.onload = function () {
    switch (xhrsnark.status) {
      case 200:
        break;
      case 404:
        break;
      case 500:
        break;
      default:
    }

    if (storageRefresh === null) {
      getRefreshInterval();
    }

    if (down || noload) {
      refreshAll();
    } else if (files || torrents) {
      refreshHeaderAndFooter();
      updateVolatile();
    } else {
    }

    if (torrents?.responseXML) {
      if (filterbar) {
        initFilterBar();
        filterbar.removeEventListener("mouseover", setLinks);
        const torrentsContainer = document.getElementById("torrents");
        if (torrentsContainer?.responseXML) {
          const newTorrents = torrents.responseXML.getElementById("torrents");
          if (newTorrents && torrentsContainer.innerHTML !== newTorrents.innerHTML) {
            torrentsContainer.innerHTML = newTorrents.innerHTML;
          }
          refreshFilters();
          checkFilterBar();
          selectFilter();
          setLinks();
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
    }

    function refreshHeaderAndFooter() {
      const thead = document.getElementById("snarkHead");
      if (thead?.responseXML) {
        const theadParent = thead.parentNode;
        const theadResponse = thead.responseXML?.getElementById("snarkHead");
        if (thead && theadResponse && !Object.is(thead.innerHTML, theadResponse.innerHTML)) {
          thead.innerHTML = theadResponse.innerHTML;
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
        checkFilterBar();
      } else if (files) {
        const dirlistResponse = xhrsnark.responseXML?.getElementById("dirlist");
        if (dirlistResponse && !Object.is(dirlist.innerHTML, dirlistResponse.innerHTML) && notfound === null) {
          dirlist.innerHTML = dirlistResponse.innerHTML;
        }
      }
      setLinks();
    }

    function setLinks() {
      const home = document.querySelector('.nav_main');
      if (home) {
        if (query !== undefined && query !== null) {
          home.href = `/i2psnark/${query}`;
        } else {
          home.href = "/i2psnark/";
        }
      }
    }

    function updateVolatile() {
      const dirlist = document.getElementById("dirlist");
      const updating = document.getElementsByClassName("volatile");
      const updatingResponse = xhrsnark.responseXML?.getElementsByClassName("volatile");
      if (updatingResponse?.length && updating.length === updatingResponse.length) {
        let updated = false;
        Array.from(updating).forEach((elem, index) => {
          if (elem.innerHTML !== updatingResponse[index].innerHTML) {
            elem.outerHTML = updatingResponse[index].outerHTML;
            updated = true;
          }
        });
        if (updated) {
        }
      } else {
        refreshAll();
      }
    }

    function updateIfVisible(snarkTable) {
      const delay = getRefreshInterval();
      let snarkUpdateId;
      if (snarkUpdateId) {
        clearTimeout(snarkUpdateId);
      }
      const onUpdate = () => {
        snarkUpdateId = setTimeout(onUpdate, delay);
      };
      onVisible(snarkTable, onUpdate, {
        hidden: () => {
          clearTimeout(snarkUpdateId);
        },
      });
    }

    initLinkToggler();

    if (typeof callback === "function") {
      callback(xhrsnark.responseXML, url);
    }

    updateIfVisible(snarkTable);

    function getRefreshInterval() {
      const interval = parseInt(xhrsnark.getResponseHeader("X-Snark-Refresh-Interval")) || 5;
      localStorage.setItem("snarkRefresh", interval);
      return interval * 1000;
    }

    xhrsnark.onerror = error => {
      console.error("XHR request failed:", error);
    };
  };
  xhrsnark.send();
}

const debouncedRefreshTorrents = debounce(refreshTorrents, 2*1000);

window.addEventListener("load", () => {
  debouncedRefreshTorrents();
});

function initSnarkRefresh() {
  const interval = (parseInt(storageRefresh) || 5) * 1000;
  if (refreshIntervalId) {
    clearInterval(refreshIntervalId);
  }
  refreshIntervalId = setInterval(() => {
    debouncedRefreshTorrents();
    initFilterBar();
    checkFilterBar();
    initToggleLog();
  }, interval);
}

document.addEventListener("DOMContentLoaded", function() {
  initSnarkRefresh();
  initLinkToggler();
  initToggleLog();
  const filterbar = document.getElementById("torrentDisplay");
  if (filterbar) {
    initFilterBar();
    checkFilterBar();
  }
});

export {initSnarkRefresh, refreshTorrents,  debouncedRefreshTorrents};