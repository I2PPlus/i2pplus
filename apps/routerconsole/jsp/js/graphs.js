/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh and configuration toggle */
/* License: AGPL3 or later */

var container = document.getElementById("allgraphs");
var configs = document.querySelectorAll("h3#graphdisplay, #gform");
var timerId = setInterval(updateGraphs, graphRefreshInterval);
var visibility = document.visibilityState;
configs.forEach(function(element) {element.style.display = "none";});
container.style.display = "none";

function initCss() {
  var graph = document.getElementsByClassName("statimage")[0];
  if (graph === null) {location.reload(true);}
  else {injectCss();}
}

function injectCss() {
  var graph = document.getElementsByClassName("statimage")[0];
  var widepanel = document.querySelector(".widepanel");
  if (graph === null) {return;}
  widepanel.id = "nographs";
  var gwrap = document.getElementById("gwrap");
  var graphWidth = graph.naturalWidth || graph.width;
  var graphHeight = graph.naturalHeight || graph.height;
  gwrap.innerHTML = ".graphContainer{width:" + (graphWidth + 4) + "px;height:" + (graphHeight + 4) + "px}";
  var delay =  Math.max(graphCount*5, 180);
  setTimeout(() => {
    container.style.display = "";
    configs.forEach(function(element) {element.style.display = "";});
    widepanel.id = "";
  }, delay);
}

function updateGraphs() {
  if (graphRefreshInterval <= 0) {return;}
  var graphs = document.getElementById("allgraphs");
  var nographs = document.getElementById("nographs");
  var images = document.getElementsByClassName("statimage");
  var totalImages = images.length;
  var imagesLoaded = 0;
  var now = Date.now();
  progressx.show(theme);
  progressx.progress(0.5);
  for (var i = 0; i < totalImages; i++) {
    var image = images[i];
    var imageSrc = image.getAttribute("src");
    (function(image, imageSrc) {
      imageSrc = imageSrc.replace(/time=\d+/, "time=" + now);
      fetch(imageSrc).then((response) => {
        if (response.ok) {
          image.src = imageSrc;
          imagesLoaded++;
        }
      }).catch((error) => {});
    })(image, imageSrc);
  }
  setTimeout(() => {progressx.hide();}, 180);
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