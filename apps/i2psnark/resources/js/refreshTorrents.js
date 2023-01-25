/* refreshTorrents.js by dr|3d */
/* Selective refresh torrents and other volatile elements in the I2PSnark UI */
/* License: AGPL3 or later */

import {initFilterBar, checkFilterBar, refreshFilters} from "/i2psnark/.resources/js/torrentDisplay.js";
//import {initFilterBar, checkFilterBar, refreshFilters} from "/themes/js/torrentDisplay.js"; // debugging

function refreshTorrents() {
  var complete = document.getElementsByClassName("completed");
  var control = document.getElementById("torrentInfoControl");
  var debug = document.getElementById("debugMode");
  var debuginfo = document.getElementsByClassName("debuginfo");
  var dirlist = document.getElementById("dirlist");
  var down = document.getElementById("down");
  var files = document.getElementById("dirInfo");
  var filterbar = document.getElementById("torrentDisplay");
  var incomplete = document.getElementsByClassName("incomplete");
  var home = document.querySelector(".nav_main");
  var info = document.getElementById("torrentInfoStats");
  var mainsection = document.getElementById("mainsection");
  var navbar = document.getElementById("navbar");
  var noload = document.getElementById("noload");
  var notfound = document.getElementById("NotFound");
  var peerinfo = document.getElementsByClassName("peerinfo");
  var query = window.location.search;
  var remaining = document.getElementById("sortRemaining");
  var savedQuery = window.localStorage.getItem("snarkURL");
  var snarkInfo = document.getElementById("snarkInfo");
  var storage = window.localStorage.getItem("filter");
  var tfoot = document.getElementById("snarkFoot");
  var thead = document.getElementById("snarkHead");
  var togglefiles = document.getElementById("toggle_files");
  var torrents = document.getElementById("torrents");
  var url = ".ajax/xhr1.html";
  var headers = new Headers();
  var pagesize = headers.get('X-Snark-Pagesize');
  var visible = document.visibilityState;
  var xhrsnark = new XMLHttpRequest();
  var xhrfilter = new XMLHttpRequest();

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
  if (navbar) {navbar.addEventListener("mouseover", setLinks, false);}
  if (debug) {debug.addEventListener("mouseover", setLinks, false);}

  if (filterbar && storage !== null && visible !== "hidden") {
    if (xhrsnark.status !== null) {xhrsnark.abort();}
    checkFilterBar();
  } else if ((torrents || noload || down) && visible !== "hidden") {
    if (xhrfilter.status !== null) {xhrfilter.abort();}
    xhrsnark.open("GET", url);
    xhrsnark.responseType = "document";
    xhrsnark.onreadystatechange = function() {

      if (xhrsnark.readyState === 4) {
        if (xhrsnark.status === 200) {
          if (torrents) {
            var torrentsResponse = xhrsnark.responseXML.getElementById("torrents");
          }

          if (down || noload) {
            refreshAll();
          }

          if (!down && !noload) {
            refreshHeaderAndFooter();
          }

          if (info) {
            var infoResponse = xhrsnark.responseXML.getElementById("torrentInfoStats");
            if (infoResponse) {
              var infoParent = info.parentNode;
              if (!Object.is(info.innerHTML, infoResponse.innerHTML)) {
                infoParent.replaceChild(infoResponse, info);
              }
            }
          }

          if (control) {
            var controlResponse = xhrsnark.responseXML.getElementById("torrentInfoControl");
            if (controlResponse) {
              var controlParent = control.parentNode;
              if (!Object.is(control.innerHTML, controlResponse.innerHTML)) {
                controlParent.replaceChild(controlResponse, control);
              }
            }
          }

          if (files || torrents) {
            updateVolatile();
          }

          if (complete) {
            var completeResponse = xhrsnark.responseXML.getElementsByClassName("completed");
            var i;
            for (i = 0; i < complete.length; i++) {
              if (completeResponse !== null && !Object.is(complete[i].innerHTML, completeResponse[i].innerHTML)) {
                complete[i].innerHTML = completeResponse[i].innerHTML;
              }
            }
          }

          if (document.getElementById("setPriority")) { // hide non-functional buttons until we fix folder.js script
            var allHigh = document.getElementById("setallhigh");
            var allNorm = document.getElementById("setallnorm");
            var allSkip = document.getElementById("setallskip");
            if (allHigh) {allHigh.remove();}
            if (allNorm) {allNorm.remove();}
            if (allSkip) {allSkip.remove();}
          }

          function updateVolatile() {
            var updating = document.getElementsByClassName("volatile");
            var updatingResponse = xhrsnark.responseXML.getElementsByClassName("volatile");
            var u;
            for (u = 0; u < updating.length; u++) {
              if (updating[u] !== null && updatingResponse[u] !== null) {
                if (!Object.is(updating[u].innerHTML, updatingResponse[u].innerHTML)) {
                  if (updating.length === updatingResponse.length) {
                    if (torrents) {updating[u].outerHTML = updatingResponse[u].outerHTML;}
                    else {updating[u].innerHTML = updatingResponse[u].innerHTML;}
                  } else {refreshAll();}
                }
              }
            }
          }

          function refreshHeaderAndFooter() {
            if (thead) {
              var theadParent = thead.parentNode;
              var theadResponse = xhrsnark.responseXML.getElementById("snarkHead");
              if (thead && theadResponse !== null && !Object.is(thead.innerHTML, theadResponse.innerHTML)) {
                thead.innerHTML = theadResponse.innerHTML;
              }
              setLinks();
            }
          }

          function refreshAll() {
            if (mainsection) {
              var mainsectionResponse = xhrsnark.responseXML.getElementById("mainsection");
              if (mainsectionResponse !== null && !Object.is(mainsection.innerHTML, mainsectionResponse.innerHTML)) {
                mainsection.innerHTML = mainsectionResponse.innerHTML;
              }
              setLinks();
            } else if (files) {
              var dirlistResponse = xhrsnark.responseXML.getElementById("dirlist");
              if (dirlistResponse !== null && !Object.is(dirlist.innerHTML, dirlistResponse.innerHTML) && !notfound) {
                dirlist.innerHTML = dirlistResponse.innerHTML;
              }
            }
          }

          function clearQuery() {window.localStorage.removeItem("queryString");}

        } else {

          function noAjax() {
            var failMessage = "<div class=\"routerdown\" id=\"down\"><b><span>Router is down<\/span><\/b><\/div>";
            if (mainsection) {mainsection.innerHTML = failMessage;}
            else {snarkInfo.innerHTML = failMessage;}
          }

          setTimeout(noAjax, 10000);
        }
      }
    };
    xhrsnark.send();
  }
}

export {refreshTorrents};