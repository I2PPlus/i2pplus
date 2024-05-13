/* I2P+ refreshElements.js by dr|z3d */
/* Refresh elements via ajax */
/* Usage: refreshElement("target selectors", "target url", refresh interval (ms)) */
/* License: AGPL3 or later */

function refreshElements(targetSelector, url, delay) {
  const visibility = document.visibilityState;
  if (visibility == "visible") {
    setInterval(function() {
      progressx.show(theme);
      progressx.progress(0.5);
      const xreq = new XMLHttpRequest();
      xreq.open("GET", url, true);
      xreq.responseType = "document";
      xreq.onload = function () {
        const targetElements = document.querySelectorAll(targetSelector);
        const targetElementsResponse = xreq.responseXML?.querySelectorAll(targetSelector);

        requestAnimationFrame(() => {
          targetElements.forEach(function(targetElement, index) {
            let targetElementResponse = targetElementsResponse[index];
            if (targetElement && targetElementResponse && !Object.is(targetElement.innerHTML, targetElementResponse.innerHTML)) {
              targetElement.innerHTML = targetElementResponse.innerHTML;
            }
          });
        });
      }
      progressx.hide();
      xreq.send();
    }, delay);
  }
}

window.addEventListener("DOMContentLoaded", progressx.hide);
