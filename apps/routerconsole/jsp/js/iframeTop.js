function iframeTop() {
  window.addEventListener("message", function(event) {
    if (event.data === "scroll-to-top") {
      var iframe = document.querySelector(".main iframe");
      if (iframe) {
        setTimeout(function() {
          iframe.contentWindow.postMessage("scroll-to-top", "*");
        }, 500);
      }
    }
  });
}

document.addEventListener("DOMContentLoaded", iframeTop);