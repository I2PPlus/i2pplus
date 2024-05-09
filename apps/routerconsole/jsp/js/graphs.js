/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh and configuration toggle */
/* License: AGPL3 or later */

var container = document.getElementById("allgraphs");
var graph = document.getElementsByClassName("statimage")[0];
var timerId = setInterval(updateGraphs, refreshInterval);
var visibility = document.visibilityState;

function initCss() {
  if (graph != null || graphWidth != graph.naturalWidth || graphHeight != graph.naturalHeight) {
    container.style.display = "none";
    graph.addEventListener("load", injectCss());
  } else {location.reload(true);}
}

function injectCss() {
  graph.addEventListener("load", function() {
    var graphWidth = graph.width;
    var graphHeight = graph.height;
    var sheet = window.document.styleSheets[0];
    sheet.insertRule(".graphContainer{width:" + (graphWidth + 4) + "px;height:" + (graphHeight + 4) + "px}", sheet.cssRules.length);
    setTimeout(() => {container.removeAttribute("style");}, 500);
  });
}

function updateGraphs() {
  if (refreshInterval <= 0) {return;}
  progressx.show("<%=theme%>");
  var graphs = document.getElementById("allgraphs");
  var nographs = document.getElementById("nographs");
  var images = document.getElementsByClassName("statimage");
  var totalImages = images.length;
  var imagesLoaded = 0;
  for (var i = 0; i < totalImages; i++) {
    var image = images[i];
    var imageUrl = image.getAttribute('src');
    (function(image, imageUrl) {
      var xhr = new XMLHttpRequest();
      xhr.open("GET", imageUrl + "?t=" + Date.now(), true);
      xhr.onload = function () {
        if (xhr.status == 200) {
          image.setAttribute("src", imageUrl + "?t=" + Date.now());
          imagesLoaded++;
          if (imagesLoaded === totalImages) {progressx.hide();}
        }
      };
      xhr.send();
    })(image, imageUrl);
  }
}

function isDown() {
  var images = document.getElementsByClassName("statimage");
  var totalImages = images.length;
  if (!images.length) {
    graphs.innerHTML = "<span id=nographs><b>No connection to Router<\/b><\/span>";
    progressx.hide();
  }
}

var config = document.getElementById("gform");
var toggle = document.getElementById("toggleSettings");
var h3 = document.getElementById("graphdisplay");
var sb = document.getElementById("sidebar");
toggle.hidden = true;

function toggleView() {
  if (toggle.checked === false) {
    config.hidden = true;
    if (h3.classList.contains("visible")) {h3.classList.remove("visible");}
  } else {
    config.hidden = false;
    h3.classList.add("visible");
    document.getElementById("gwidth").focus();
    if (sb !== null && sb.scrollHeight < document.body.scrollHeight) {
      setTimeout(() => {window.scrollTo({top: document.body.scrollHeight, behavior: "smooth"});}, 500);
    }
  }
}

toggleView();
toggle.addEventListener("click", toggleView);
window.addEventListener("DOMContentLoaded", progressx.hide);

graph.addEventListener("load", initCss());
setTimeout(isDown, 60000);
