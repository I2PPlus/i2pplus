/* I2P+ graphSingle.js by dr|z3d */
/* Ajax graph refresh and button handling */
/* License: AGPL3 or later */

let graphImage;
const LS_KEY = "singleGraphHideLegend";

function getLocalStorageHideLegend() {
  try {
    const stored = localStorage.getItem(LS_KEY);
    return stored === "true";
  } catch (e) {
    return false;
  }
}

function setLocalStorageHideLegend(value) {
  try {
    localStorage.setItem(LS_KEY, value ? "true" : "false");
  } catch (e) {}
}

function getHideLegendFromURL() {
  const urlParams = new URLSearchParams(window.location.search);
  const value = urlParams.get("hideLegend");
  if (value === "true") return true;
  if (value === "false") return false;
  return null;
}

function updateHideLegendInURL(url, hideLegend) {
  const param = "hideLegend=" + (hideLegend ? "true" : "false");
  if (url.includes("hideLegend=")) {
    return url.replace(/hideLegend=(true|false)/, param);
  }
  return url + (url.includes("?") ? "&" : "?") + param;
}

function syncLocalStorageWithURL() {
  const urlHideLegend = getHideLegendFromURL();
  if (urlHideLegend !== null) {
    setLocalStorageHideLegend(urlHideLegend);
  }
}

function initButtons() {
  document.addEventListener("click", function(event) {
    if (event.target.closest("#graphopts a")) {
      event.preventDefault();
      const link = event.target.closest("#graphopts a");
      const href = link.getAttribute("href");

      // Ensure the URL matches localStorage for all links
      const storedHideLegend = getLocalStorageHideLegend();
      const adjustedHref = updateHideLegendInURL(href, storedHideLegend);

      // Check if this is a Hide/Show Legend link
      if (link.textContent.includes("Legend")) {
        // Toggle localStorage state
        const newHideLegend = !storedHideLegend;
        setLocalStorageHideLegend(newHideLegend);

        // Update URL with new state
        const newHref = updateHideLegendInURL(href, newHideLegend);

        fetch(newHref)
          .then(response => response.text())
          .then(data => {
            const temp = document.createElement("div");
            temp.innerHTML = data;

            // Update the graph image
            const updatedImgSrc = temp.querySelector("#graphSingle").src;
            graphImage.src = updatedImgSrc;

            // Update the graph options
            const graphopts = document.getElementById("graphopts");
            const updatedOpts = temp.querySelector("#graphopts").innerHTML;
            graphopts.innerHTML = updatedOpts;

            // Update the address bar
            history.pushState(null, "", newHref);
            syncLocalStorageWithURL();
            initCss();
          })
          .catch(error => console.error("Error updating graph: ", error));
      } else {
        // Handle other buttons with localStorage-synced URL
        fetch(adjustedHref)
          .then(response => response.text())
          .then(data => {
            const temp = document.createElement("div");
            temp.innerHTML = data;

            // Update the graph image
            const updatedImgSrc = temp.querySelector("#graphSingle").src;
            graphImage.src = updatedImgSrc;

            // Update the graph options
            const graphopts = document.getElementById("graphopts");
            const updatedOpts = temp.querySelector("#graphopts").innerHTML;
            graphopts.innerHTML = updatedOpts;

            // Update the address bar
            history.pushState(null, "", adjustedHref);
            syncLocalStorageWithURL();
            initCss();
          })
          .catch(error => console.error("Error updating graph: ", error));
      }
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
  s.innerHTML = ".graphContainer{width:" + w + "px}";
  document.head.appendChild(s);
}

function refreshGraph() {
  if (graphRefreshInterval > 0) {
    var graph = document.getElementById("single");
    graphImage = document.getElementById("graphSingle");

    function updateGraphURL() {
      var currentImgSrc = graphImage.src;
      var graphURL = currentImgSrc.replace(/time=\d+/, "time=" + Date.now());
      // Preserve hideLegend in refresh
      const storedHideLegend = getLocalStorageHideLegend();
      graphURL = updateHideLegendInURL(graphURL, storedHideLegend);
      graphImage.src = graphURL;
      graphImage.onload = function() {initCss();};
    }
    updateGraphURL();

    if (graph && document.visibilityState == "visible") {
      setInterval(function() {
        progressx.show("<%=theme%>");
        updateGraphURL();
        progressx.hide();
      }, graphRefreshInterval);
    }
  }
}

function checkLocalStorageAndRedirect() {
  const urlHideLegend = getHideLegendFromURL();

  if (urlHideLegend === null) {
    const storedHideLegend = getLocalStorageHideLegend();
    if (storedHideLegend) {
      const newHref = updateHideLegendInURL(window.location.href, true);
      window.location.href = newHref;
      return true;
    }
  } else {
    // Sync localStorage with URL
    setLocalStorageHideLegend(urlHideLegend);
  }
  return false;
}

document.addEventListener("DOMContentLoaded", () => {
  // Check localStorage and redirect if needed
  if (checkLocalStorageAndRedirect()) {
    return;
  }

  graphImage = document.getElementById("graphSingle");
  initButtons();
  progressx.hide();
  graphImage.onload = function() {initCss();}
  refreshGraph();
});