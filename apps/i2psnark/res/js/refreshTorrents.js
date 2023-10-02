/* I2PSnark refreshTorrents.js by dr|z3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {onVisible} from "./onVisible.js";
import {initFilterBar} from "./torrentDisplay.js";
import {initLinkToggler, magnetToast, attachMagnetListeners, linkToggle} from "./toggleLinks.js";
import {initToggleLog} from "./toggleLog.js";
import {Lightbox} from "./lightbox.js";
import {initSnarkAlert} from "./snarkAlert.js";

const debugMode = document.getElementById("debugMode");
const files = document.getElementById("dirInfo");
const filterbar = document.getElementById("torrentDisplay");
const home = document.querySelector(".nav_main");
const mainsection = document.getElementById("mainsection");
const query = window.location.search;
const screenlog = document.getElementById("screenlog");
const snarkHead = document.getElementById("snarkHead");
const storageRefresh = window.localStorage.getItem("snarkRefresh");
const xhrsnark = new XMLHttpRequest();
let refreshIntervalId;
let refreshTimeoutId;
let requestInProgress = false;

function refreshTorrents(callback) {
  const complete = document.getElementsByClassName("completed");
  const control = document.getElementById("torrentInfoControl");
  const dirlist = document.getElementById("dirlist");
  const down = document.getElementById("down");
  const filterEnabled = localStorage.hasOwnProperty("snarkFilter") !== null;
  const info = document.getElementById("torrentInfoStats");
  const noload = document.getElementById("noload");
  const notfound = document.getElementById("NotFound");
  const storage = window.localStorage.getItem("snarkFilter");
  const torrents = document.getElementById("torrents");

  if (xhrsnark.readyState === XMLHttpRequest.UNSENT) {
    requestInProgress = true;

    xhrsnark.onload = function() {
      if (typeof callback === "function") {
        callback(xhrsnark.responseXML, getURL());
      }
      requestInProgress = false;
    };
    doRefresh();
  }

  if (document.getElementById("ourDest") === null) {
    const childElems = document.querySelectorAll("#snarkHead th:not(.torrentAction)>*");
    if (snarkHead !== null) {
      document.getElementById("snarkHead").classList.add("initializing");
      childElems.forEach((elem) => {elem.style.opacity = "0";});
    }
  }

  requestInProgress = true;

  if (!storageRefresh) {
    getRefreshInterval();
  }

  if (down || noload) {
    window.requestAnimationFrame(refreshAll);
  } else if (files || torrents) {
    window.requestAnimationFrame(refreshHeaderAndFooter);
    window.requestAnimationFrame(updateVolatile);
  }

  if (torrents?.responseXML) {
      //filterbar.removeEventListener("mouseover", setLinks);
      const torrentsContainer = document.getElementById("torrents");
      if (torrentsContainer?.responseXML) {
        const newTorrents = xhrsnark.responseXML.getElementById("torrents");
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
    const snarkTr = document.querySelectorAll("#snarkTbody tr.volatile");
    const updating = document.querySelectorAll("#snarkTbody tr.volatile, #messages");
    const updatingResponse = xhrsnark.responseXML?.querySelectorAll("#snarkTbody tr.volatile, #messages");

    let updated = false;

    if (updatingResponse && updatingResponse.length && Array.from(updating).every(function (elem) {
      return Array.from(updatingResponse).includes(elem);
    })) {
      updated = false;
    }

    for (let i = 0; i < updating.length; i++) {
      if (updatingResponse && updating.length === updatingResponse.length) {
        const updatingTds = updating[i].querySelectorAll("td:not(.details.data):not(.magnet):not(.trackerLink)");
        const updatingTdsResponse = updatingResponse[i]?.querySelectorAll("td:not(.details.data):not(.magnet):not(.trackerLink)");
        if (updatingResponse && updating[i].innerHTML !== updatingResponse[i]?.innerHTML) {
          if (updating[i].classList.contains("even")) {
            updating[i].outerHTML = updatingResponse[i].outerHTML;
          } else {
            updating[i].outerHTML = updatingResponse[i].outerHTML.replace(/(<tr class="[^"]*)even([^"]*">)/, "$1$2");
          }
          updated = true;
        } else if (updatingResponse) {
          for (let j = 0; j < updatingTds.length; j++) {
            updatingTds[j].innerHTML = updatingTdsResponse[j].innerHTML;
            updated = true;
          }
        }
      } else {
        window.requestAnimationFrame(refreshAll);
      }
    }
    if (updated) {
      window.requestAnimationFrame(refreshHeaderAndFooter);
    } else {
      window.requestAnimationFrame(refreshAll);
    }
  }

  function refreshHeaderAndFooter() {
    const snarkHead = document.getElementById("snarkHead");
    const snarkHeadThs = document.querySelectorAll("#snarkHead th:not(.torrentLink)");
    const snarkFoot = document.getElementById("snarkFoot");
    const snarkFootThs = document.querySelectorAll("#snarkFoot th");
    if (snarkHead) {
      const snarkHeadResponse = xhrsnark.responseXML.getElementById("snarkHead");
      const snarkHeadThsResponse = xhrsnark.responseXML.querySelectorAll("#snarkHead th:not(.torrentLink)");
      if (snarkHead && snarkHeadResponse && !Object.is(snarkHead.innerHTML, snarkHeadResponse.innerHTML)) {
        Array.from(snarkHeadThs).forEach((elem, index) => {
          if (elem.innerHTML !== snarkHeadThsResponse[index].innerHTML) {
            elem.innerHTML = snarkHeadThsResponse[index].innerHTML;
          }
        });
      }
    }
    if (snarkFoot) {
      const snarkFootResponse = xhrsnark.responseXML.getElementById("snarkFoot");
      const snarkFootThsResponse = xhrsnark.responseXML.querySelectorAll("#snarkFoot th");
      if (snarkFoot && snarkFootResponse && !Object.is(snarkFoot.innerHTML, snarkFootResponse.innerHTML)) {
        Array.from(snarkFootThs).forEach((elem, index) => {
          if (elem.innerHTML !== snarkFootThsResponse[index].innerHTML) {
            elem.innerHTML = snarkFootThsResponse[index].innerHTML;
          }
        });
      }
    }
    if (debugMode) {
      debugMode.load = function() {
        initHandlers();
      };
    }
  }

  function getRefreshInterval() {
    const interval = parseInt(xhrsnark.getResponseHeader("X-Snark-Refresh-Interval")) || 5;
    localStorage.setItem("snarkRefresh", interval);
    return interval * 1000;
  }

  requestInProgress = false;

  if (filterbar) {
    initFilterBar();
  }

  xhrsnark.onerror = function (error) {
    noAjax(5000);
    if (refreshTimeoutId) {
      clearTimeout(refreshTimeoutId);
    }
    refreshTimeoutId = setTimeout(() => refreshTorrents(initHandlers), 1000);
    requestInProgress = false;
  };

}

function getURL() {
  const filterEnabled = localStorage.getItem("snarkFilter") !== null;
  const baseUrl = "/i2psnark/.ajax/xhr1.html";
  const url = filterEnabled ? baseUrl + query + (query ? "&" : "?") + "ps=9999" : baseUrl + query;
  return url;
}

let linkTogglerReady = false;
let magnetListenersReady = false;
let magnetToastReady = false;
let setLinksReady = false;
let toggleLogReady = false;

function initHandlers() {
  if (!setLinksReady) {
    setLinks();
    setLinksReady = true;
  }
  if (!linkTogglerReady) {
    initLinkToggler();
    linkTogglerReady = true;
  }
  if (!magnetToastReady) {
    magnetToast();
    magnetToastReady = true;
  }
  if (!magnetListenersReady && debugMode) {
    attachMagnetListeners();
    magnetListenersReady = true;
  }
  if (screenlog && !toggleLogReady) {
    initToggleLog();
    toggleLogReady = true;
  }
  if (screenlog) {
    initSnarkAlert();
  }
}

function setLinks(query) {
  const home = document.querySelector(".nav_main");
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
}

async function initSnarkRefresh() {
  const interval = (parseInt(storageRefresh) || 5) * 1000;
  clearInterval(refreshIntervalId);
  refreshIntervalId = setInterval(async () => {
    await doRefresh();
    initHandlers();
  }, interval);
  if (files && document.getElementById("lightbox")) {
    const lightbox = new Lightbox();
    lightbox.load();
  }
}

async function doRefresh() {
  xhrsnark.open("GET", getURL(), true);
  xhrsnark.responseType = "document";
  xhrsnark.send();
  await new Promise((resolve, reject) => {
    xhrsnark.onload = () => {
      resolve();
      refreshTorrents();
      initHandlers();
    };
    xhrsnark.onerror = () => {
      reject(xhrsnark.status);
    };
  });
}

document.addEventListener("DOMContentLoaded", () => {
  if (mainsection) {
    onVisible(mainsection, () => {
      doRefresh();
    });
  } else if (files) {
    onVisible(files, () => {
      doRefresh();
    });
  }
});

export {initSnarkRefresh, refreshTorrents, xhrsnark, refreshIntervalId};