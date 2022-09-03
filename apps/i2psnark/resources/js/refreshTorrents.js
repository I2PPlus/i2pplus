function refreshTorrents(timestamp) {

  var mainsection = document.getElementById("mainsection");
  var snarkInfo = document.getElementById("snarkInfo");
  var torrents = document.getElementById("torrents");
  var thead = document.getElementById("snarkHead");
  var tfoot = document.getElementById("snarkFoot");
  var noload = document.getElementById("noload");
  var down = document.getElementById("down");
  var togglefiles = document.getElementById("toggle_files");
  var filterbar = document.getElementById("torrentDisplay");

  var query = window.location.search;

  if (torrents || noload)
    var url = ".ajax/xhr1.html";
  else
    var url = location.href;
  if (query)
    url += query;

  var xhrsnark = new XMLHttpRequest();

  xhrsnark.open("GET", url);
  xhrsnark.setRequestHeader("If-Modified-Since", "Sat, 1 Jan 2000 00:00:00 GMT");
  xhrsnark.responseType = "document";
  xhrsnark.onreadystatechange = function() {

    if (xhrsnark.readyState == 4) {
      if (xhrsnark.status == 200) {

        var info = document.getElementById("torrentInfoStats");
        var control = document.getElementById("torrentInfoControl");
        var files = document.getElementById("dirInfo");
        var remaining = document.getElementById("sortRemaining");
        var complete = document.getElementsByClassName("completed");
        var incomplete = document.getElementsByClassName("incomplete");
        var peerinfo = document.getElementsByClassName("peerinfo");
        var debuginfo = document.getElementsByClassName("debuginfo");
        if (torrents) {
          var torrentsResponse = xhrsnark.responseXML.getElementById("torrents");
          var filterbarResponse = xhrsnark.responseXML.getElementById("torrentDisplay");
        }

        if (down || noload || (torrents && (!filterbar && typeof filterbarResponse !== "undefined")))
          window.requestAnimationFrame(refreshAll);

        if (!down && !noload)
          refreshHeaderAndFooter();

        if (info) {
          var infoResponse = xhrsnark.responseXML.getElementById("torrentInfoStats");
          if (infoResponse) {
            var infoParent = info.parentNode;
            if (!Object.is(info.innerHTML, infoResponse.innerHTML))
              infoParent.replaceChild(infoResponse, info);
          }
        }

        if (control) {
          var controlResponse = xhrsnark.responseXML.getElementById("torrentInfoControl");
          if (controlResponse) {
            var controlParent = control.parentNode;
            if (!Object.is(control.innerHTML, controlResponse.innerHTML))
              controlParent.replaceChild(controlResponse, control);
          }
        }

        if (files || torrents) {
            window.requestAnimationFrame(updateVolatile);
        }

        if (complete) {
          function updateCompleted() {
            var completeResponse = xhrsnark.responseXML.getElementsByClassName("completed");
            var i;
            for (i = 0; i < complete.length; i++) {
              if (typeof completeResponse !== "undefined" && !Object.is(complete[i].innerHTML, completeResponse[i].innerHTML))
                complete[i].innerHTML = completeResponse[i].innerHTML;
            }
          }
          updateCompleted();
        }

        var results = document.getElementById("filterResults");
        if (results) {
          results.remove();
        }

        if (document.getElementById("setPriority")) { // hide non-functional buttons until we fix folder.js script
          var allHigh = document.getElementById("setallhigh");
          var allNorm = document.getElementById("setallnorm");
          var allSkip = document.getElementById("setallskip");
          if (allHigh)
            allHigh.remove();
          if (allNorm)
            allNorm.remove();
          if (allSkip)
            allSkip.remove();
        }

        function updateVolatile(timestamp) {
          var updating = document.getElementsByClassName("volatile");
          var updatingResponse = xhrsnark.responseXML.getElementsByClassName("volatile");
          var i;
          for (i = 0; i < updating.length; i++) {
            if (typeof updating[i] !== "undefined" && typeof updatingResponse[i] !== "undefined") {
              if (!Object.is(updating[i].innerHTML, updatingResponse[i].innerHTML)) {
                if (updating.length === updatingResponse.length) {
                  if (torrents)
                    updating[i].outerHTML = updatingResponse[i].outerHTML;
                  else
                    updating[i].innerHTML = updatingResponse[i].innerHTML;
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
            if (thead && typeof theadResponse !== "undefined" && !Object.is(thead.innerHTML, theadResponse.innerHTML))
              thead.innerHTML = theadResponse.innerHTML;
              thead.querySelector("th.peerCount").addEventListener("click", toggleShowPeers, false);
              initFilterBar();
          }
        }

        function refreshAll(timestamp) {
          if (mainsection) {
            var mainsectionResponse = xhrsnark.responseXML.getElementById("mainsection");
            if (typeof mainsectionResponse !== "undefined" && !Object.is(mainsection.innerHTML, mainsectionResponse.innerHTML))
              mainsection.innerHTML = mainsectionResponse.innerHTML;
              mainsection.querySelector("th.peerCount").addEventListener("click", toggleShowPeers, false);
              initFilterBar();
          } else if (files) {
            var dirlist = document.getElementById("dirlist");
            var notfound = document.getElementById("NotFound");
            var dirlistResponse = xhrsnark.responseXML.getElementById("dirlist");
            if (typeof dirlistResponse !== "undefined" && !Object.is(dirlist.innerHTML, dirlistResponse.innerHTML) && !notfound)
              dirlist.innerHTML = dirlistResponse.innerHTML;
          }
        }

      } else {

        function noAjax() {
          var failMessage = "<div class=\"routerdown\" id=\"down\"><b><span>Router is down<\/span><\/b><\/div>";
          if (mainsection)
            mainsection.innerHTML = failMessage;
          else
            snarkInfo.innerHTML = failMessage;
        }

        setTimeout(noAjax, 10000);

      }
    }
  }
  xhrsnark.send();
}

export {refreshTorrents};