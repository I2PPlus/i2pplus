/* refreshTorrents.js by dr|3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {initFilterBar, checkFilterBar, refreshFilters} from "./torrentDisplay.js";
import {onVisible} from "./onVisible.js";
import {initLinkToggler} from "./toggleLinks.js";

function refreshTorrents() {
  const COMPLETE_CLASS = "completed";
  const DEBUG_MODE_ID = "debugMode";
  const NAVBAR_ID = "navbar";
  const FILTER_ID = "torrentDisplay";
  const INCOMPLETE_CLASS = "incomplete";
  const SNARK_INFO_ID = "snarkInfo";
  const TOGGLE_FILES_ID = "toggle_files";
  const TORRENTS_ID = "torrents";
  const TFOOT_ID = "snarkFoot";
  const THEAD_ID = "snarkHead";

  let complete = document.getElementsByClassName(COMPLETE_CLASS);
  let control = document.getElementById("torrentInfoControl");
  let debug = document.getElementById(DEBUG_MODE_ID);
  let debuginfo = document.getElementsByClassName("debuginfo");
  let dirlist = document.getElementById("dirlist");
  let down = document.getElementById("down");
  let files = document.getElementById("dirInfo");
  let filterbar = document.getElementById(FILTER_ID);
  let incomplete = document.getElementsByClassName(INCOMPLETE_CLASS);
  let home = document.querySelector(".nav_main");
  let info = document.getElementById("torrentInfoStats");
  let mainsection = document.getElementById("mainsection");
  let navbar = document.getElementById(NAVBAR_ID);
  let noload = document.getElementById("noload");
  let notfound = document.getElementById("NotFound");
  let peerinfo = document.getElementsByClassName("peerinfo");
  let query = window.location.search;
  let savedQuery = window.localStorage.getItem("snarkURL");
  let snarkInfo = document.getElementById(SNARK_INFO_ID);
  let storage = window.localStorage.getItem("filter");
  let tfoot = document.getElementById(TFOOT_ID);
  let thead = document.getElementById(THEAD_ID);
  let togglefiles = document.getElementById(TOGGLE_FILES_ID);
  let torrents = document.getElementById(TORRENTS_ID);
  let url = ".ajax/xhr1.html";
  let headers = new Headers();
  let pagesize = headers.get('X-Snark-Pagesize');
  let visible = document.visibilityState;
  let xhrsnark = new XMLHttpRequest();
  let xhrfilter = new XMLHttpRequest();

  if (query) {
    if (storage !== null && filterbar) {
      url += query + "&ps=9999";
    } else {
      url += query;
    }
  } else if (storage !== null && filterbar) {
    url += "?ps=9999";
  }

  function setLinks() {
    home.href = "/i2psnark/";

    if (debug !== null) {
      if (savedQuery !== null) {
        debug.href = home.href + savedQuery;
      } else if (query != null) {
        debug.href = home.href + query + "&p=2";
      }
    }

    if (home && query) {
      home.href = "/i2psnark/" + query;
    }
  }

  setLinks();
  navbar?.addEventListener("mouseover", setLinks, false);
  debug?.addEventListener("mouseover", setLinks, false);

  const filterEnabled = localStorage.hasOwnProperty("filter");

  if (filterbar && filterEnabled) {
    initLinkToggler();
    onVisible(filterbar, () => {
      checkFilterBar();
    });
    if (down || noload) {
      refreshAll();
    }
  } else if ((torrents || noload || down)) {
    xhrsnark.open("GET", url);
    xhrsnark.responseType = "document";
    xhrsnark.onreadystatechange = function() {
      if (xhrsnark.readyState === 4) {
        if (xhrsnark.status === 200) {
          if (torrents) {
            const torrentsResponse = xhrsnark.responseXML.getElementById(TORRENTS_ID);
            initLinkToggler();
          }

          if (down || noload) {
            refreshAll();
          } else {
            if (files || torrents) {
              refreshHeaderAndFooter();
              updateVolatile();
            }
          }

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

          if (complete) {
            const completeResponse = xhrsnark.responseXML.getElementsByClassName(COMPLETE_CLASS);
            for (let i = 0; i < complete.length && completeResponse !== null; i++) {
              if (!Object.is(complete[i].innerHTML, completeResponse[i]?.innerHTML)) {
                complete[i].innerHTML = completeResponse[i].innerHTML;
              }
            }
          }

          // hide non-functional buttons until we fix folder.js script
          if (document.getElementById("setPriority")) {
            const allHigh = document.getElementById("setallhigh");
            const allNorm = document.getElementById("setallnorm");
            const allSkip = document.getElementById("setallskip");
            allHigh?.remove();
            allNorm?.remove();
            allSkip?.remove();
          }

          function updateVolatile() {
            const updating = document.getElementsByClassName("volatile");
            const updatingResponse = xhrsnark.responseXML.getElementsByClassName("volatile");
            for (let u = 0; u < updating?.length && updatingResponse?.length; u++) {
              if (updating.length === updatingResponse.length) {
                if (updating[u].innerHTML !== updatingResponse[u].innerHTML) {
                  updating[u].outerHTML = updatingResponse[u].outerHTML;
                }
              } else {
                refreshAll();
              }
            }
          }

          function refreshHeaderAndFooter() {
            if (thead) {
              const theadParent = thead.parentNode;
              const theadResponse = xhrsnark.responseXML.getElementById(THEAD_ID);
              if (thead && theadResponse !== null && !Object.is(thead.innerHTML, theadResponse.innerHTML)) {
                thead.innerHTML = theadResponse.innerHTML;
              }
              setLinks();
              initLinkToggler();
            }
          }

          function refreshAll() {
            if (mainsection) {
              const mainsectionResponse = xhrsnark.responseXML.getElementById("mainsection");
              if (mainsectionResponse !== null && mainsection !== mainsectionResponse) {
                mainsection.innerHTML = mainsectionResponse.innerHTML;
              }
              setLinks();
              initLinkToggler();

            } else if (files) {
              const dirlistResponse = xhrsnark.responseXML.getElementById("dirlist");
              if (dirlistResponse !== null && !Object.is(dirlist.innerHTML, dirlistResponse.innerHTML) && !notfound) {
                dirlist.innerHTML = dirlistResponse.innerHTML;
              }
            }
          }

          function clearQuery() {window.localStorage.removeItem("queryString");}

        } else {
          function noAjax() {
            const failMessage = "<div class=routerdown id=down><span>Router is down</span></div>";
            if (mainsection) {
              mainsection.innerHTML = failMessage;
            } else {
              snarkInfo.innerHTML = failMessage;
            }
          }
          setTimeout(noAjax, 5000);
        }
      }
    };
    xhrsnark.send();
    initLinkToggler();
  }
}

export {refreshTorrents};
