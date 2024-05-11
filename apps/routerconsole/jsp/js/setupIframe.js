function setupIframe() {
  if (window.top !== window.self) {
    const html = document.documentElement;
    if (!html.classList.contains("iframed")) {html.classList.add("iframed");}
    document.addEventListener("DOMContentLoaded", function() {endOfPage();});
  }
}

function endOfPage() {
  if (!endOfPage) {
    var end = document.createElement("span");
    end.setAttribute("id", "endOfPage");
    end.setAttribute("data-iframe-height", "");
    document.body.appendChild(end);
  }
}

setTimeout(setupIframe, 0);