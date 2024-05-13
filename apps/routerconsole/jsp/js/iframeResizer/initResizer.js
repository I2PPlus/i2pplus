/* I2P+ initResizer.js by dr|z3d */
/* Initialize iframeResizer and scroll the parent and child windows to top */
/* License: AGPL3 or later */

function initResizer(frameId) {
  if (!frameId) {
    setTimeout(function() {
      initResizer();
    }, 500);
    return;
  }

  const iframe = document.getElementById(frameId);
  const iframeChild = iframe.contentWindow || iframe.contentDocument.defaultView;
  const bodyTag = iframeChild.document.body;

  iFrameResize({
    interval: 0,
    heightCalculationMethod: "taggedElement",
    warningTimeout: 0,

    onInit: function() {
      requestAnimationFrame(() => {
        iframeChild.scrollTo(0, 0);
        window.parent.scrollTo(0, 0);
      });
    }

  });
  progressx.hide();
}