/**
 * @module graphSingle
 * @description Handles AJAX graph refresh and button interactions for single graph
 * pages. Manages hide/show legend state via localStorage and URL synchronization,
 * and provides periodic graph image updates.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/** @type {HTMLImageElement|null} */
let graphImage;
/** @type {string} */
const LS_KEY = "singleGraphHideLegend";

/**
 * Reads the hide legend preference from localStorage.
 * @function getLocalStorageHideLegend
 * @returns {boolean} True if legend should be hidden, false otherwise
 */
function getLocalStorageHideLegend() {
  try {
    const stored = localStorage.getItem(LS_KEY);
    return stored === "true";
  } catch (e) {
    return false;
  }
}

/**
 * Saves the hide legend preference to localStorage.
 * @function setLocalStorageHideLegend
 * @param {boolean} value - Whether the legend should be hidden
 * @returns {void}
 */
function setLocalStorageHideLegend(value) {
  try {
    localStorage.setItem(LS_KEY, value ? "true" : "false");
  } catch (e) {}
}

/**
 * Reads the hideLegend query parameter from the current URL.
 * @function getHideLegendFromURL
 * @returns {boolean|null} True/false from URL, or null if not present
 */
function getHideLegendFromURL() {
  const urlParams = new URLSearchParams(window.location.search);
  const value = urlParams.get("hideLegend");
  if (value === "true") { return true; }
  if (value === "false") { return false; }
  return null;
}

/**
 * Updates or appends the hideLegend parameter in the given URL string.
 * @function updateHideLegendInURL
 * @param {string} url - The URL to update
 * @param {boolean} hideLegend - The new value for the hideLegend parameter
 * @returns {string} The updated URL string
 */
function updateHideLegendInURL(url, hideLegend) {
  const param = "hideLegend=" + (hideLegend ? "true" : "false");
  if (url.includes("hideLegend=")) {
    return url.replace(/hideLegend=(true|false)/, param);
  }
  return url + (url.includes("?") ? "&" : "?") + param;
}

/**
 * Synchronizes localStorage hide legend state with the URL parameter if present.
 * @function syncLocalStorageWithURL
 * @returns {void}
 */
function syncLocalStorageWithURL() {
  const urlHideLegend = getHideLegendFromURL();
  if (urlHideLegend !== null) {
    setLocalStorageHideLegend(urlHideLegend);
  }
}

/**
 * Sets up click delegation for graph option links, handling legend toggling
 * and graph updates via fetch.
 * @function initButtons
 * @returns {void}
 */
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

/**
 * Initializes CSS injection, clearing any existing interval and setting up
 * the injection timer.
 * @function initCss
 * @returns {void}
 */
function initCss() {
  let graphcss;
  if (timer) {clearInterval(timer);}
  if (!graphcss || !graphImage) {var timer = setInterval(() => injectCss(), 500);}
  else {injectCss();}
}

/**
 * Injects a style element with the graph container width based on the image dimensions.
 * @function injectCss
 * @returns {void}
 */
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

/**
 * Sets up periodic graph image refresh at the configured interval.
 * @function refreshGraph
 * @returns {void}
 */
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

/**
 * Checks localStorage for a hide legend preference and redirects the page
 * if the URL doesn't reflect the stored preference.
 * @function checkLocalStorageAndRedirect
 * @returns {boolean} True if a redirect was initiated, false otherwise
 */
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