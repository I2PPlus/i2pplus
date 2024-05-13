/* I2P+ initResizer.js by dr|z3d */
/* Initialize iframeResizer and scroll the parent and child windows to top */
/* License: AGPL3 or later */

function initResizer(frameId) {
  if (!frameId) {
    setTimeout(function() {initResizer();}, 500);
    return;
  }
  const iframe = document.getElementById(frameId);
  if (iframe.getAttribute("data-iframe-resized") === "true") {return;}
  const iframeChild = iframe.contentWindow || iframe.contentDocument.defaultView;
  iFrameResize({
    interval: 0,
    heightCalculationMethod: "taggedElement",
    warningTimeout: 0,
    onInit: function() {
      setTimeout(function() {
        iframeChild.scrollTo(0,0);
        window.parent.scrollTo(0,0);
      }, 0);
    }
  }, "#" + frameId);
}