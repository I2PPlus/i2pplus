/* I2P+ initResizer.js by dr|z3d */
/* Initialize iframeResizer and scroll the parent and child windows to top */
/* and listen for 'scrollToElement' event data to scroll to specified element */
/* License: AGPL3 or later */

function initResizer(frameId) {
  if (!frameId) {setTimeout(function() {initResizer();}, 500); return;}

  const iframe = document.getElementById(frameId);
  const iframeChild = iframe.contentWindow || iframe.contentDocument.defaultView;

  iFrameResize({
    interval: 0,
    heightCalculationMethod: "taggedElement",
    warningTimeout: 0,
    inPageLinks: true,
    onInit: function() {
      requestAnimationFrame(() => {
        iframeChild.scrollTo(0, 0);
        window.parent.scrollTo(0, 0);
      });

      // Add listener for scrolling inside the iframe
      window.addEventListener("message", function(event) {
        if (event.data.command === "scrollToElement") {
          const element = iframeChild.document.getElementById(event.data.id);
          scrollToElement(element, iframeChild);
        }
      }, false);
    }
  });

  function scrollToElement(element, windowObj = window) {
    if (element) {
      const elementPosition = element.getBoundingClientRect().top;
      const scrollPosition = elementPosition + (windowObj !== window ? windowObj.pageYOffset : window.pageYOffset);
      windowObj.scrollTo({ top: scrollPosition, behavior: "smooth" });
    }
  }
}