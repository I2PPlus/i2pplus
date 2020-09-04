// refresh the sidebar mini graph every 3 seconds

function refreshGraph() {
  var minigraph = document.getElementById("minigraph");
  var routerdown = document.getElementById("down");
  if (minigraph && !routerdown) {
    const ctx = minigraph.getContext("2d");
    const image = new Image(245, 50);
    image.onload = renderGraph;
    image.src = "/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=" + new Date().getTime();
    ctx.globalCompositeOperation = "copy";
    ctx.globalAlpha = 1;

    function renderGraph() {
      minigraph.width = 245;
      minigraph.height = 50;
      ctx.drawImage(this, 0, 0, 245, 50);
    }
  }

  var req = new XMLHttpRequest();
  var uri = location.pathname.substring(1);
  req.open('GET', '/xhr1.jsp?requestURI=' + uri + '&t=' + new Date().getTime(), true);
  req.responseType = "document";
  req.overrideMimeType('text/html');
  req.setRequestHeader('Accept', 'text/html');
  req.setRequestHeader('Cache-Control', 'no-store');
  req.onreadystatechange = function() {
    if (req.readyState == 4 && req.status == 200) {
      if (minigraph) {
        var minigraphResponse = req.responseXML.getElementById('minigraph');
        minigraph = minigraphResponse;
      }
    }
  }
  req.send();
  setTimeout(refreshGraph, 3000);
}

refreshGraph();
