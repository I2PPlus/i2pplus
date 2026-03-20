/**
 * @module setupIframe
 * @description Detects if the page is running inside an iframe and adds the
 * "iframed" class to the HTML element. Also appends an end-of-page marker
 * for iframe height calculation.
 * @license AGPL3 or later
 */

/**
 * Checks if the page is in an iframe context and applies the "iframed" class.
 * @function setupIframe
 * @returns {void}
 */
function setupIframe() {
  if (window.top !== window.self) {
    const html = document.documentElement;
    if (!html.classList.contains("iframed")) {html.classList.add("iframed");}
    document.addEventListener("DOMContentLoaded", function() {
      endOfPage();
    });
  }
}

/**
 * Creates and appends an end-of-page marker element for iframe height detection.
 * @function endOfPage
 * @returns {void}
 */
function endOfPage() {
  var endOfPage = document.getElementById("endOfPage");
  if (!endOfPage) {
    var end = document.createElement("span");
    end.setAttribute("id", "endOfPage");
    end.setAttribute("data-iframe-height", "");
    document.body.appendChild(end);
  }
}

setTimeout(setupIframe, 0);
