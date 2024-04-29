/* I2PSnark refreshTorrents.js by dr|z3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {onVisible} from "./onVisible.js";
import {initFilterBar, showBadge} from "./torrentDisplay.js";
import {initLinkToggler} from "./toggleLinks.js";
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
const torrents = document.getElementById("torrents");
const xhrsnark = new XMLHttpRequest();
let refreshIntervalId;
let screenLogIntervalId;
let refreshTimeoutId;
let debugging = false;

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

  if (document.getElementById("ourDest") === null) {
    const childElems = document.querySelectorAll("#snarkHead th:not(.torrentAction)>*");
    if (snarkHead !== null) {
      document.getElementById("snarkHead").classList.add("initializing");
      childElems.forEach((elem) => {elem.style.opacity = "0";});
    }
  }

  if (!storageRefresh) {
    getRefreshInterval();
    if (debugging) {console.log("getRefreshInterval()");}
  }

  setLinks(query);

  if (files || torrents) {
    window.requestAnimationFrame(updateVolatile);
  } else if (down || noload) {
    window.requestAnimationFrame(refreshAll);
  }

  function refreshAll(delay) {
    return new Promise((resolve, reject) => {
      try {
        const files = document.getElementById("dirInfo");
        const mainsection = document.getElementById("mainsection");
        const notfound = document.getElementById("NotFound");
        const dirlist = document.getElementById("dirlist");
        if (mainsection) {
          const mainsectionResponse = xhrsnark.responseXML?.getElementById("mainsection");
          if (mainsectionResponse && mainsection.innerHTML !== mainsectionResponse.innerHTML) {
            new Promise(resolve => {
              if (torrents) {
                const torrentsResponse = xhrsnark.responseXML.getElementById("torrents");
                window.requestAnimationFrame(() => {
                  torrents.innerHTML = torrentsResponse.innerHTML;
                });
                resolve();
              } else {
                window.requestAnimationFrame(() => {
                  mainsection.innerHTML = mainsectionResponse.innerHTML;
                });
              }
            }).then(() => {
              resolve();
            });
            return;
          }
        } else if (files) {
          const dirlistResponse = xhrsnark.responseXML?.getElementById("dirlist");
          if (dirlistResponse && !Object.is(dirlist.innerHTML, dirlistResponse.innerHTML) && notfound === null) {
            dirlist.innerHTML = dirlistResponse.innerHTML;
          }
        }
        if (debugging) {
          console.log("refreshAll()");
        }
      } catch (error) {
        reject(error);
      }
    });
  }

  let updated = false;
  let requireFullRefresh = true;
  let requireHeadFootRefresh = true;

  function updateVolatile() {
    if (!xhrsnark.responseXML) {return;}
    const updating = document.querySelectorAll("#snarkTbody tr");
    const updatingResponse = xhrsnark.responseXML?.querySelectorAll("#snarkTbody tr");
    const updatingCells = document.querySelectorAll("#snarkTbody tr.volatile td:not(.magnet):not(.trackerLink):not(.details.data)");
    const updatingCellsResponse = xhrsnark.responseXML?.querySelectorAll("#snarkTbody tr.volatile td:not(.magnet):not(.trackerLink):not(.details.data)");

    if (torrents) {
      if (filterbar) {
        const activeBadge = filterbar.querySelector("#torrentDisplay .filter .badge:not(:empty)");
        const activeBadgeResponse = xhrsnark.responseXML?.querySelector("#torrentDisplay .filter .badge:not(:empty)");
        if (activeBadge && activeBadgeResponse && activeBadge.textContent !== activeBadgeResponse.textContent) {
          activeBadge.textContent = activeBadgeResponse.textContent;
        }
        if (!xhrsnark.responseXML) {return;}
        const filterBarResponse = xhrsnark.responseXML.getElementById("torrentDisplay");
        if (!filterBar && filterBarResponse) {
          const mainsection = document.getElementById("mainsection");
          const mainsectionResponse = xhrsnark.responseXML?.getElementById("mainsection");
          window.requestAnimationFrame(() => {mainsection.innerHTML = mainsectionResponse.innerHTML;});
        }
      }
      if (updatingResponse && updating.length === updatingResponse.length) {
        for (let i = 0; i < updating.length; i++) {
          if (updating[i].innerHTML !== updatingResponse[i].innerHTML) {
            if (updating[i].outerHTML !== updatingResponse[i].outerHTML) {
              window.requestAnimationFrame(() => {
                updating[i].outerHTML = updatingResponse[i].outerHTML;
              });
              window.requestAnimationFrame(refreshHeaderAndFooter);
              updated = true;
              requireFullRefresh = false;
            }
          }
        }
      } else if (requireFullRefresh && updatingResponse) {
        if (debugging) {console.log("html: " + updating.length + " / xhr: " + updatingResponse.length);}
        window.requestAnimationFrame(refreshAll);
        updated = true;
        requireHeadFootRefresh = false;
        requireFullRefresh = false;
      }
    } else if (dirlist?.responseXML) {
      if (info) {
        const infoResponse = xhrsnark.responseXML?.getElementById("torrentInfoStats");
        if (infoResponse) {
          const infoParent = info.parentNode;
          if (!Object.is(info.innerHTML, infoResponse.innerHTML)) {
            infoParent.replaceChild(infoResponse, info);
          }
        }
      }
      if (control) {
        const controlResponse = xhrsnark.responseXML?.getElementById("torrentInfoControl");
        if (controlResponse) {
          const controlParent = control.parentNode;
          if (!Object.is(control.innerHTML, controlResponse.innerHTML)) {
            controlParent.replaceChild(controlResponse, control);
          }
        }
      }
      if (complete?.length && dirlist?.responseXML) {
        const completeResponse = xhrsnark.responseXML.getElementsByClassName("completed");
        if (completeResponse) {
          for (let i = 0; i < complete.length && completeResponse?.length; i++) {
            if (!Object.is(complete[i].innerHTML, completeResponse[i]?.innerHTML)) {
              complete[i].innerHTML = completeResponse[i].innerHTML;
            }
          }
        }
      }
    }
    if (debugging) {
      console.log("updateVolatile()");
    }
  }

  function refreshHeaderAndFooter() {
    if (!xhrsnark.responseXML) {return;}
    const snarkFoot = document.getElementById("snarkFoot");
    const snarkFootResponse = xhrsnark.responseXML?.getElementById("snarkFoot");
    const snarkHead = document.getElementById("snarkHead");
    const snarkHeadResponse = xhrsnark.responseXML?.getElementById("snarkHead");

    if (snarkHead && snarkHeadResponse && snarkHead.innerHTML !== snarkHeadResponse.innerHTML) {
      snarkHead.innerHTML = snarkHeadResponse.innerHTML
    }

    if (snarkFoot && snarkFootResponse && snarkFoot.innerHTML !== snarkFootResponse.innerHTML) {
      snarkFoot.innerHTML = snarkFootResponse.innerHTML;
    }
  }

  function getRefreshInterval() {
    const interval = parseInt(xhrsnark.getResponseHeader("X-Snark-Refresh-Interval")) || 5;
    localStorage.setItem("snarkRefresh", interval);
    return interval * 1000;
  }

  xhrsnark.onerror = function (error) {
    noAjax(5000);
    if (refreshTimeoutId) {
      clearTimeout(refreshTimeoutId);
    }
    refreshTimeoutId = setTimeout(() => refreshTorrents(initHandlers), 1000);
  };

}

function refreshScreenLog(callback) {
  const xhrsnarklog = new XMLHttpRequest();
  const lowerSection = document.getElementById("lowersection");
  const addNotify = lowerSection.querySelector("#addNotify");
  const createNotify = lowerSection.querySelector("#createNotify");
  const screenlog = document.getElementById("messages");
  const toast = document.getElementById("toast");
  xhrsnarklog.open("GET", "/i2psnark/.ajax/xhrscreenlog.html");
  xhrsnarklog.responseType = "document";
  xhrsnarklog.onload = function () {
    const notifyResponse = xhrsnarklog.responseXML.getElementById("notify");
    const screenlogResponse = xhrsnarklog.responseXML.getElementById("messages");
    const toastResponse = xhrsnarklog.responseXML.getElementById("toast");
    if (screenlog && screenlogResponse && screenlog.innerHTML !== screenlogResponse.innerHTML) {
      screenlog.innerHTML = screenlogResponse.innerHTML;
    }
    if (xhrsnarklog.readyState === 4 && xhrsnarklog.status === 200) {
      setTimeout(function() {
        if (addNotify.innerHTML !== notifyResponse.innerHTML) {
          addNotify.innerHTML = notifyResponse.innerHTML;
        }
        if (createNotify.innerHTML !== notifyResponse.innerHTML) {
          createNotify.innerHTML = notifyResponse.innerHTML;
        }
      }, 500);
      toast.innerHTML = toastResponse.innerHTML;
      if (callback) {callback();}
    }
  };
  xhrsnarklog.send();
  if (debugging) {console.log("Updated screenlog");}
}

function getURL() {
  const baseUrl = "/i2psnark/.ajax/xhr1.html";
  var url = baseUrl + query;
  return url;
}

function initHandlers() {
  window.requestAnimationFrame(() => {
    setLinks();
    initLinkToggler();
    if (screenlog) {
      initSnarkAlert();
    }
    if (filterbar) {
      showBadge();
    }
    if (debugging) {console.log("initHandlers()");}
  });
}

function setLinks(query) {
  const home = document.querySelector(".nav_main");
  if (home) {
    if (query !== undefined && query !== null) {home.href = `/i2psnark/${query}`;}
    else {home.href = "/i2psnark/";}
  }
}

function noAjax(delay) {
  var failMessage = "<div class=routerdown id=down><span>Router is down</span></div>";
  var targetElement = mainsection || snarkInfo;
  setTimeout(function() {
    if (targetElement) {
      targetElement.innerHTML = failMessage;
    }
  }, delay);
}

async function initSnarkRefresh() {
  if (refreshIntervalId) {
    clearInterval(refreshIntervalId);
  }
  onVisible(mainsection, () => {
      const refreshInterval = (parseInt(storageRefresh) || 5) * 1000;
      const screenLogInterval = 3000;
    try {
      refreshIntervalId = setInterval(async () => {
        try {
          await doRefresh();
          await refreshScreenLog();
          await initToggleLog();
        } catch {};
      }, refreshInterval);
      const lighboxEnabled = document.getElementById("lightbox");
      if (files && lightboxEnabled) {
        const lightbox = new Lightbox();
        lightbox.load();
      }
      const events = document._events?.click || [];
      for (let i = 0; i < events.length; i++) {
        document.removeEventListener("click", events[i]);
        refreshOnSubmit();
      }
    } catch {}
  });
}

const REQUEST_TIMEOUT = 5000;
function doRefresh() {
  return new Promise((resolve, reject) => {
    xhrsnark.timeout = REQUEST_TIMEOUT;
    xhrsnark.open("GET", getURL(), true);
    xhrsnark.responseType = "document";
    xhrsnark.onload = () => {
      isXHRSynced();
      window.requestAnimationFrame(refreshTorrents);
      initHandlers();
      resolve();
    };
    xhrsnark.onerror = () => {
      reject(xhrsnark.status);
    };
    xhrsnark.send();
  }).finally(() => {
  });
}

function isXHRSynced() {
  if (debugging === false) {return;}
  const updating = document.querySelectorAll("#snarkTbody tr, #torrents #snarkFoot th");
  const updatingResponse = xhrsnark.responseXML?.querySelectorAll("#snarkTbody tr, #torrents #snarkFoot th");
  if (updatingResponse && debugging) {console.log("html elements: " + updating.length + " / xhr elements: " + updatingResponse.length);}
  if (updating.length != updatingResponse.length) {
    for (let i = 0; i < updatingResponse.length; i++) {
      if (updating[i] && updating[i].outerHTML != updatingResponse[i].outerHTML) {
        continue;
      }
      if (debugging) {console.log("Missing element: Class: " + updating[i]?.className + ", ID: " + updating[i]?.id);}
      return false;
    }
  } else {
    return true;
  }
}

function refreshOnSubmit() {
  document.addEventListener("click", function(event) {
    if (event.target.tagName === "INPUT" && event.target.type === "submit") {
      refreshScreenLog();
    }
  });
}

function countSnarks() {
  const rowCount = document.querySelectorAll("tr.volatile").length;
  return rowCount;
}

export {initSnarkRefresh, refreshTorrents, refreshScreenLog, xhrsnark, refreshIntervalId, getURL, countSnarks};