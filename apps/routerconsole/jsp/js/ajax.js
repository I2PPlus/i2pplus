var fails = 0;

function ajax(url, target, refresh) {
  // native XMLHttpRequest object
  if (window.XMLHttpRequest) {
    req = new XMLHttpRequest();
    req.onreadystatechange = function() {ajaxDone(url, target, refresh);};
    req.open("GET", url, true);
    // IE https://www.jamesmaurer.com/ajax-refresh-problem-w-ie-not-refreshing.asp
    req.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
    req.send(null);
    // IE/Windows ActiveX version
  } else if (window.ActiveXObject) {
    req = new ActiveXObject("Microsoft.XMLDOM");
    if (req) {
      req.onreadystatechange = function() {ajaxDone(target);};
      req.open("GET", url, true);
      // IE https://www.jamesmaurer.com/ajax-refresh-problem-w-ie-not-refreshing.asp
      req.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
      req.send(null);
    }
  }
}

function ajaxDone(url, target, refresh) {
  // only if req is "loaded"
  if (req.readyState == 4) {
    // only if "OK"
    if (req.status == 200) {
      fails = 0;
      results = req.responseText;

    // conditionally display graph so ajax call doesn't interfere with refreshGraph.js
    var graph = document.getElementById("sb_graphcontainer");
    if (graph) {
        graph.style.backgroundImage = "url(/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&time=" + new Date().getTime();
    }

      document.getElementById(target).innerHTML = results;
      //document.getElementsbyClassName("hideifdown").style.display="block";
    } else if (fails == 0) {
      // avoid spurious message if cancelled by user action
      fails++;
    } else {
      document.getElementById(target).innerHTML = failMessage;
      //document.getElementByClassName("hideifdown").style.display="none";
    }

    setTimeout(function() {ajax(url, target, refresh);}, refresh);
  }
}
