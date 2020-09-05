function refreshTorrents() {
  var xhrsnark = new XMLHttpRequest();
  var mainsection = document.getElementById("mainsection");
  var torrents = document.getElementById("snarkTorrents");
  if (torrents)
    var torrentsParent = torrents.parentNode;
  var thead = document.getElementById("snarkHead");
  var noload = document.getElementById("noload");
  var down = document.getElementById("down");
  var url = ".ajax/xhr1.html";
  var query = location.search;
  if (query)
    url += query;
  xhrsnark.open('GET', url);
  xhrsnark.setRequestHeader("If-Modified-Since", "Sat, 1 Jan 2000 00:00:00 GMT");
  xhrsnark.responseType = "document";
  xhrsnark.onreadystatechange = function () {

    if (xhrsnark.readyState == 4) {
      if (xhrsnark.status == 200) {

        if (mainsection && down) {
          var mainsectionResponse = xhrsnark.responseXML.getElementById("mainsection");
          if (mainsectionResponse) {
            mainsection.innerHTML = mainsectionResponse.innerHTML;
          }
        }

        if (!down) {
          var torrentsResponse = xhrsnark.responseXML.getElementById("snarkTorrents");
          var theadResponse = xhrsnark.responseXML.getElementById("snarkHead");
        }
        var screenlog = document.getElementById("screenlog");
        var tfoot = document.getElementById("snarkFoot");
        var even = document.getElementsByClassName("snarkTorrentEven");
        var odd = document.getElementsByClassName("snarkTorrentOdd");
        var peerinfo = document.getElementsByClassName("peerinfo");
        var debuginfo = document.getElementsByClassName("debuginfo");

        if (torrentsResponse && (noload || !thead)) {
              if (!Object.is(torrents.innerHTML, torrentsResponse.innerHTML))
                torrentsParent.replaceChild(torrentsResponse, torrents);
//                torrents.outerHTML = torrentsResponse.outerHTML;
        }

        if (screenlog) {
          var screenlogResponse = xhrsnark.responseXML.getElementById("screenlog");
          if (screenlogResponse) {
            var screenlogParent = screenlog.parentNode;
            if (!Object.is(screenlog.innerHTML, screenlogResponse.innerHTML))
              screenlogParent.replaceChild(screenlogResponse, screenlog);
          }
        }

        if (thead) {
          if (thead && theadResponse) {
            var theadParent = thead.parentNode;
            if (!Object.is(thead.innerHTML, theadResponse.innerHTML))
              theadParent.replaceChild(theadResponse, thead);
          } else if (!thead) {
            torrentsParent.replaceChild(torrentsResponse.outerHTML, torrents.outerHTML);
          }
        }

        if (tfoot) {
          var tfootResponse = xhrsnark.responseXML.getElementById("snarkFoot");
          if (tfootResponse) {
            var tfootParent = tfoot.parentNode;
            if (!Object.is(tfoot.innerHTML, tfootResponse.innerHTML))
              tfootParent.replaceChild(tfootResponse, tfoot);
          }
        }

        if (even) {
          var evenResponse = xhrsnark.responseXML.getElementsByClassName("snarkTorrentEven");
          if (evenResponse) {
            var i;
            for (i = 0; i < even.length; i++) {
              if (!Object.is(even[i].innerHTML, evenResponse[i].innerHTML))
                even[i].outerHTML = evenResponse[i].outerHTML;
              else if (even.length != evenResponse.length)
                torrentsParent.replaceChild(torrentsResponse, torrents);
            }
          }
        }

        if (odd) {
          var oddResponse = xhrsnark.responseXML.getElementsByClassName("snarkTorrentOdd");
          if (oddResponse) {
            var i;
            for (i = 0; i < odd.length; i++) {
              if (!Object.is(odd[i].innerHTML, oddResponse[i].innerHTML))
                odd[i].outerHTML = oddResponse[i].outerHTML;
              else if (odd.length != oddResponse.length)
                torrentsParent.replaceChild(torrentsResponse, torrents);
            }
          }
        }


        if (peerinfo) {
          var peerinfoResponse = xhrsnark.responseXML.getElementsByClassName("peerinfo");
          if (peerinfoResponse) {
            var i;
            for (i = 0; i < peerinfo.length; i++) {
              if (!Object.is(peerinfo[i].innerHTML, peerinfoResponse[i].innerHTML))
                peerinfo[i].outerHTML = peerinfoResponse[i].outerHTML;
              else if (peerinfo.length != peerinfoResponse.length)
                torrentsParent.replaceChild(torrentsResponse, torrents);
            }
          }
        }

        if (debuginfo) {
          var debuginfoResponse = xhrsnark.responseXML.getElementsByClassName("debuginfo");
          if (debuginfoResponse) {
            var i;
            for (i = 0; i < debuginfo.length; i++) {
              if (!Object.is(debuginfo[i].innerHTML, debuginfoResponse[i].innerHTML))
                debuginfo[i].innerHTML = debuginfoResponse[i].innerHTML;
            }
          }
        }

        var results = document.getElementById("filterResults");
        if (results) {
          results.remove();
        }

      } else {
            var failMessage = "<div class=\"routerdown\" id=\"down\"><b><span>Router is down<\/span><\/b><\/div>";
            mainsection.innerHTML = failMessage;
      }

    }
  }
  xhrsnark.send();
}

export {refreshTorrents};