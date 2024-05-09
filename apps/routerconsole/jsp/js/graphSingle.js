/* I2P+ graphSingle.js by dr|z3d */
/* Ajax graph refresh and button handling */
/* License: AGPL3 or later */

let graphImage;

function initButtons() {
  document.addEventListener("click", function(event) {
    if (event.target.closest("#graphopts a")) {
      event.preventDefault();
      const href = event.target.getAttribute("href");
      fetch(href)
        .then(response => response.text())
        .then(data => {
          const temp = document.createElement('div');
          temp.innerHTML = data;

          // Update the graph image
          const updatedImgSrc = temp.querySelector("#graphSingle").src;
          graphImage.src = updatedImgSrc;

          // Update the graph options
          const graphopts = document.getElementById("graphopts");
          const updatedOpts = temp.querySelector("#graphopts").innerHTML;
          graphopts.innerHTML = updatedOpts;

          // Update the address bar
          history.pushState(null, '', href);
          initCss();
        })
        .catch(error => console.error('Error fetching graph single content:', error));
    }
  });
}

function initCss() {
  let graphcss;
  if (timer) {clearInterval(timer);}
  if (!graphcss || !graphImage) {var timer = setInterval(() => injectCss(), 500);}
  else {injectCss();}
}

function injectCss() {
  if (!graphImage) {setTimeout(() => {injectCss();}, 100);}
  var graphWidth = graphImage.naturalWidth;
  var graphHeight = graphImage.naturalHeight;
  var graphcss = document.getElementById("graphcss");
  if (graphcss) {graphcss.remove();}
  var s = document.createElement("style");
  s.setAttribute("id", "graphcss");
  var w = graphWidth !== 0 ? graphWidth + 4 : graphImage.width + 4;
  var h = graphHeight !== 0 ? graphHeight + 4 : graphImage.height + 4;
  s.innerHTML = ".graphContainer{width:" + w + "px;height:" + h + "px}";
  document.head.appendChild(s);
}

function refreshGraph() {
  if (refreshInterval > 0) {
    var graph = document.getElementById("single");
    graphImage = document.getElementById("graphSingle");

    function updateGraphURL() {
      var currentImgSrc = graphImage.src;
      var graphURL = currentImgSrc + "&t=" + Date.now();
      graphImage.src = graphURL;
      graphImage.onload = function() {
        initCss();
        progressx.hide();
      };
    }
    updateGraphURL();
    if (graph && document.visibilityState == "visible") {
      setInterval(function() {
        progressx.show("<%=theme%>");
        updateGraphURL();
        progressx.hide();
      }, refreshInterval);
    }
  }
}

document.addEventListener("DOMContentLoaded", () => {
  graphImage = document.getElementById("graphSingle");
  initButtons();
  progressx.hide();
  graphImage.onload = function() {initCss();}
  refreshGraph();
});