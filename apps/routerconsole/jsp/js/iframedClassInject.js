// inject iframed class to embedded body tag

function injectClass(f) {
  f.className += ' iframed ';
  var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
  if (!doc.body.classList.contains("iframed"))
    doc.body.className += ' iframed';
}

function iframeResizer(f) {
  iFrameResize({log: false, interval: 0, heightCalculationMethod: 'taggedElement', warningTimeout: 0}, f);
}

document.addEventListener("DOMContentLoaded", function() {
  setupFrame();
}, true);