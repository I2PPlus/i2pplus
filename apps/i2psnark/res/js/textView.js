/* I2P+ textView.js for I2PSnark by dr|z3d */
/* Inline text viewer */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {
  const viewLinks = [
    'td.fileIcon>a',
    'td.snarkFileName>a[href$=".css"]',
    'td.snarkFileName>a[href$=".nfo"]',
    'td.snarkFileName>a[href$=".srt"]',
    'td.snarkFileName>a[href$=".txt"]',
  ];

  const fileLinks = document.querySelectorAll(":where(" + viewLinks.join(",") + ")");
  const snarkFileNameLinks = document.querySelectorAll(":where(" + viewLinks.slice(1).join(",") + ")");

  snarkFileNameLinks.forEach((link) => {
    const newTabLink = document.createElement("a");
    newTabLink.href = link.href;
    newTabLink.target = "_blank";
    newTabLink.title = "Open in new tab";
    const newTabSpan = document.createElement("span");
    newTabSpan.className = "newtab";
    newTabSpan.appendChild(newTabLink);
    link.parentNode.insertBefore(newTabSpan, link.nextSibling);
  });

  function loadCSS(href) {
    const snarkTheme = document.getElementById("snarkTheme");
    var css = document.createElement("link");
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = href;
    snarkTheme.parentNode.insertBefore(css, snarkTheme);
  }

  function createTextViewer() {
    const viewerWrapper = document.createElement("div");
    const viewerContent = document.createElement("div");
    viewerWrapper.id = "textview";
    viewerWrapper.setAttribute("hidden", "");
    viewerContent.id = "textview-content";
    viewerContent.style.position = "relative";
    viewerWrapper.appendChild(viewerContent);
    document.body.appendChild(viewerWrapper);
    loadCSS("/i2psnark/.res/textView.css");
    return { viewerWrapper, viewerContent };
  }

  const { viewerWrapper, viewerContent } = createTextViewer();

  fileLinks.forEach((link) => {
    link.addEventListener("click", function (event) {
      const filename = decodeURIComponent(link.href.split("/").pop());
      const lastDotIndex = filename.lastIndexOf(".");
      if (lastDotIndex !== -1) {
        const fileExtension = filename.substring(lastDotIndex + 1).toLowerCase();
        if (fileExtension !== "txt" && fileExtension !== "srt") { viewerContent.classList.add("pre"); }
        if (["nfo", "txt", "css", "srt"].includes(fileExtension)) {
          event.preventDefault();
          fetch(link.href).then((response) => response.text()).then((data) => {
            viewerContent.innerHTML = data;
            viewerWrapper.removeAttribute("hidden");
          }).catch((error) => { });
        }
      }
    });
  });

  viewerWrapper.addEventListener("click", function () {
    if (event.target !== viewerContent) { viewerWrapper.setAttribute("hidden", ""); }
  });
});