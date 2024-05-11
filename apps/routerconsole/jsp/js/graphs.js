/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh and configuration toggle */
/* License: AGPL3 or later */

var container = document.getElementById("allgraphs");
var configs = document.querySelectorAll("h3#graphdisplay, #gform");
var graph = document.getElementsByClassName("statimage")[0];
var timerId = setInterval(updateGraphs, graphRefreshInterval);
var visibility = document.visibilityState;
container.style.display = "none";

function initCss() {
  if (graph != null || graphWidth != graph.naturalWidth || graphHeight != graph.naturalHeight) {
    configs.forEach(function(element) {element.style.display = "none";});
    injectCss();
  } else {location.reload(true);}
}

function injectCss() {
  if (!graph) {initCss(); return;}
  var graphWidth = graph.width;
  var graphHeight = graph.height;
  var gwrap = document.getElementById("gwrap");
  var widepanel = document.querySelector(".widepanel");
  widepanel.id = "nographs";
  gwrap.innerText = ".graphContainer{width:" + (graphWidth + 4) + "px;height:" + (graphHeight + 4) + "px}";
  setTimeout(() => {
    container.removeAttribute("style");
    configs.forEach(function(element) {element.removeAttribute("style")});
    widepanel.removeAttribute("id");
  }, 500);
}

function updateGraphs() {
  if (graphRefreshInterval <= 0) {return;}
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
      var newUrl = imageUrl.replace(/time=\d+/, "time=" + Date.now());
      xhr.open("GET", newUrl, true);
      xhr.onload = function () {
        if (xhr.status == 200) {
          image.setAttribute("src", newUrl);
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
  if (!toggle) {return;}
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
toggle?.addEventListener("click", toggleView);
window.addEventListener("DOMContentLoaded", function() {
  progressx.hide();
  initCss();
});
setTimeout(isDown, 60000);