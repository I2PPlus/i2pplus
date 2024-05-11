function setupIframe() {
  if (window.top !== window.self) {
    const html = document.documentElement;
    if (!html.classList.contains("iframed")) {html.classList.add("iframed");}
    document.addEventListener("DOMContentLoaded", function() {
      endOfPage();
      setTimeout(function() {
        window.parent.postMessage("scroll-to-top", "*");
      }, 50);
    });
  }
}

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

window.addEventListener("message", function(event) {
  if (event.data === "scroll-to-top") {
    window.scrollTo(0, 0);
  }
});