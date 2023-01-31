// inject iframed class to embedded body tag

function injectClass(f) {
  var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
  if (!doc.body.classList.contains("iframed")) {
    doc.body.classList.add("iframed");
  }
}

function iframeResizer(f) {
  iFrameResize({interval: 0, heightCalculationMethod: 'taggedElement', warningTimeout: 0}, f);
}

document.addEventListener("DOMContentLoaded", function() {
  setupFrame();
}, true);
