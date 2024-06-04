/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh and configuration toggle */
/* License: AGPL3 or later */

const config = document.getElementById("gform");
const h3 = document.getElementById("graphdisplay");
const sb = document.getElementById("sidebar");
const toggle = document.getElementById("toggleSettings");
const configs = document.querySelectorAll("h3#graphdisplay, #gform");
const container = document.getElementById("allgraphs");
const visibility = document.visibilityState;
let timerId = setInterval(updateGraphs, graphRefreshInterval);
configs.forEach(function(element) {element.style.display = "none";});
container.style.display = "none";
toggle.hidden = true;

function initCss() {
  const graph = document.getElementsByClassName("statimage")[0];
  if (graph === null) {location.reload(true);}
  else {injectCss();}
}

function injectCss() {
  const graph = document.getElementsByClassName("statimage")[0];
  const widepanel = document.querySelector(".widepanel");
  if (graph === null) {return;}
  widepanel.id = "nographs";
  const gwrap = document.getElementById("gwrap");
  const graphWidth = graph.naturalWidth || graph.width;
  const graphHeight = graph.naturalHeight || graph.height;
  gwrap.innerHTML = ".graphContainer{width:" + (graphWidth + 4) + "px;height:" + (graphHeight + 4) + "px}";
  let delay =  Math.max(graphCount*5, 180);
  setTimeout(() => {
    container.style.display = "";
    configs.forEach(function(element) {element.style.display = "";});
    widepanel.id = "";
  }, delay);
}

function updateGraphs() {
  if (graphRefreshInterval <= 0) {return;}
  const images = document.querySelectorAll(".statimage");
  const now = Date.now();
  let imagesLoaded = 0;
  progressx.show(theme);
  progressx.progress(0.5);
  images.forEach((image) => {
    const imageSrc = image.getAttribute("src").replace(/time=\d+/, "time=" + now);
    fetch(imageSrc).then((response) => {
      if (response.ok) {
        requestAnimationFrame(() => {image.src = imageSrc;});
        imagesLoaded++;
      }
    }).catch((error) => {});
  });
  setTimeout(() => {progressx.hide();}, 180);
}

function isDown() {
  const images = document.querySelectorAll(".statimage");
  let totalImages = images.length;
  if (!images.length) {
    graphs.innerHTML = "<span id=nographs><b>No connection to Router<\/b><\/span>";
    progressx.hide();
  }
}

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