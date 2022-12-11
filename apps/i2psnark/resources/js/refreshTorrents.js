import {initFilterBar} from "/.resources/js/torrentDisplay.js";

function refreshTorrents(timestamp) {

  var complete = document.getElementsByClassName("completed");
  var control = document.getElementById("torrentInfoControl");
  var debuginfo = document.getElementsByClassName("debuginfo");
  var debug = tfoot.getElementById("#debugMode");
  var dirlist = document.getElementById("dirlist");
  var down = document.getElementById("down");
  var files = document.getElementById("dirInfo");
  var filterbar = document.getElementById("torrentDisplay");
  var home = navbar.querySelector(".nav_main");
  var incomplete = document.getElementsByClassName("incomplete");
  var info = document.getElementById("torrentInfoStats");
  var mainsection = document.getElementById("mainsection");
  var navbar = document.getElementById("navbar");
  var noload = document.getElementById("noload");
  var notfound = document.getElementById("NotFound");
  var peerinfo = document.getElementsByClassName("peerinfo");
  var query = window.location.search;
  var remaining = document.getElementById("sortRemaining");
  var savedQuery = window.localStorage.getItem("queryString");
  var snarkInfo = document.getElementById("snarkInfo");
  var tfoot = document.getElementById("snarkFoot");
  var thead = document.getElementById("snarkHead");
  var togglefiles = document.getElementById("toggle_files");
  var torrents = document.getElementById("torrents");
  var url = location.href;
  var xhrsnark = new XMLHttpRequest();

  if (torrents || noload) {
    if (savedQuery !== null) {
      url = location.href + savedQuery;
    }
  }

  function getQuery() {
    if (query !== null && torrents !== null) {
      url = "/i2psnark/" + query;
      window.localStorage.setItem("queryString", query);
      if (savedQuery !== null) {
        query = savedQuery;
        url = "/i2psnark/" + savedQuery;
      }
    }
  }

  function setLinks() {
    home.href = url;
    getQuery();
    if (debug !== null) {
      debug.href = savedQuery;
    } else if (home) {
      home.href = "/i2psnark/" + savedQuery;
    }
  }

  setLinks();
  navbar.addEventListener("mouseover", setLinks, false);
  if (debug) {
    debug.addEventListener("mouseover", setLinks, false);
  }

  if (torrents || noload || down) {
  xhrsnark.open("GET", url);
  xhrsnark.setRequestHeader("If-Modified-Since", "Sat, 1 Jan 2000 00:00:00 GMT");
  xhrsnark.responseType = "document";
  xhrsnark.onreadystatechange = function() {

    if (xhrsnark.readyState === 4) {
      if (xhrsnark.status === 200) {
        if (torrents) {
          var torrentsResponse = xhrsnark.responseXML.getElementById("torrents");
          if (filterbar) {
            var filterbarResponse = xhrsnark.responseXML.getElementById("torrentDisplay");
            if (!filterbar && filterbarResponse !== null) {
              window.requestAnimationFrame(refreshAll);
            }
          }
        }

        if (down || noload) {
          window.requestAnimationFrame(refreshAll);
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
            window.requestAnimationFrame(updateVolatile);
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

        var results = document.getElementById("filterResults");
        if (results) {
          results.remove();
        }

        if (document.getElementById("setPriority")) { // hide non-functional buttons until we fix folder.js script
          var allHigh = document.getElementById("setallhigh");
          var allNorm = document.getElementById("setallnorm");
          var allSkip = document.getElementById("setallskip");
          if (allHigh) {
            allHigh.remove();
          }
          if (allNorm) {
            allNorm.remove();
          }
          if (allSkip) {
            allSkip.remove();
          }
        }

        function updateVolatile(timestamp) {
          var updating = document.getElementsByClassName("volatile");
          var updatingResponse = xhrsnark.responseXML.getElementsByClassName("volatile");
          var u;
          for (u = 0; u < updating.length; u++) {
            if (updating[u] !== null && updatingResponse[u] !== null) {
              if (!Object.is(updating[u].innerHTML, updatingResponse[u].innerHTML)) {
                if (updating.length === updatingResponse.length) {
                  if (torrents) {
                    updating[u].outerHTML = updatingResponse[u].outerHTML;
                  } else {
                    updating[u].innerHTML = updatingResponse[u].innerHTML;
                  }
                } else {
                  window.requestAnimationFrame(refreshAll);
                }
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
            if (filterbar) {
              initFilterBar();
            }
          }
        }

        function refreshAll(timestamp) {
          if (mainsection) {
            var mainsectionResponse = xhrsnark.responseXML.getElementById("mainsection");
            if (mainsectionResponse !== null && !Object.is(mainsection.innerHTML, mainsectionResponse.innerHTML)) {
              mainsection.innerHTML = mainsectionResponse.innerHTML;
            }
            setLinks();
            if (filterbar) {
              initFilterBar();
            }
          } else if (files) {
            var dirlistResponse = xhrsnark.responseXML.getElementById("dirlist");
            if (dirlistResponse !== null && !Object.is(dirlist.innerHTML, dirlistResponse.innerHTML) && !notfound) {
              dirlist.innerHTML = dirlistResponse.innerHTML;
            }
          }
        }

        function clearFilter() {
          window.localStorage.removeItem("filter");
        }

        function clearQuery() {
          window.localStorage.removeItem("queryString");
        }

      } else {

        function noAjax() {
          var failMessage = "<div class=\"routerdown\" id=\"down\"><b><span>Router is down<\/span><\/b><\/div>";
          if (mainsection) {
            mainsection.innerHTML = failMessage;
          } else {
            snarkInfo.innerHTML = failMessage;
          }
        }
        setTimeout(noAjax, 10000);
      }
    }
  };
  xhrsnark.send();
  }
}

export {refreshTorrents};