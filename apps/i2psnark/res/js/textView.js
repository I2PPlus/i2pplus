/* I2P+ textView.js for I2PSnark by dr|z3d */
/* Inline text viewer */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {

  const viewLinks = [
    'td.fileIcon>a',
    'td.snarkFileName>a[href$=".css"]',
    'td.snarkFileName>a[href$=".js"]',
    'td.snarkFileName>a[href$=".json"]',
    'td.snarkFileName>a[href$=".nfo"]',
    'td.snarkFileName>a[href$=".sh"]',
    'td.snarkFileName>a[href$=".srt"]',
    'td.snarkFileName>a[href$=".txt"]',
  ];

  const iframedStyles = ".textviewer,.textviewer #torrents.main,.textviewer #i2psnarkframe" +
                        "{margin:0!important;padding:0!important;width:100%;height:100%!important;position:absolute;top:0;left:0;" +
                        "bottom:0;right:0;overflow:hidden;border:0!important;contain:paint}" +
                        ".textviewer h1{display:none}";

  const doc = document;
  const parentDoc = window.parent.document;
  const isIframed = doc.documentElement.classList.contains("iframed") || window.parent;
  const fileLinks = doc.querySelectorAll(":where(" + viewLinks.join(",") + ")");
  const snarkFileNameLinks = doc.querySelectorAll(":where(" + viewLinks.slice(1).join(",") + ")");
  const supportedFileTypes = ["css", "csv", "js", "json", "nfo", "txt", "sh", "srt"];
  const numberedFileExts = ["css", "js", "sh"];

  let listenersActive = false;

  const fragments = Array.from(snarkFileNameLinks).map((link) => {
    const fragment = doc.createDocumentFragment();
    const newTabLink = doc.createElement("a");
    const newTabSpan = doc.createElement("span");
    newTabLink.href = link.href;
    newTabLink.target = "_blank";
    newTabLink.title = "Open in new tab";
    newTabSpan.className = "newtab";
    newTabSpan.appendChild(newTabLink);
    fragment.appendChild(newTabSpan);
    return fragment;
  });

  Array.from(snarkFileNameLinks).forEach((link, index) => {
    link.parentNode.insertBefore(fragments[index], link.nextSibling);
  });

  function loadCSS(href) {
    const snarkTheme = doc.getElementById("snarkTheme");
    const css = doc.createElement("link");
    css.rel = "stylesheet";
    css.href = href;
    snarkTheme.parentNode.insertBefore(css, snarkTheme);
  }

  function loadIframeCSS() {
    if (!isIframed) {return;}
    const cssIframed = doc.createElement("style");
    cssIframed.id = "textviewCss";
    cssIframed.innerHTML = iframedStyles;
    parentDoc.body.appendChild(cssIframed);
  }

  let viewerWrapper, viewerContent, viewerFilename;

  function createTextViewer() {
    if (!viewerWrapper) {
      loadCSS("/i2psnark/.res/textView.css");
      viewerWrapper = doc.createElement("div");
      viewerContent = doc.createElement("div");
      viewerFilename = doc.createElement("div");
      viewerWrapper.id = "textview";
      viewerWrapper.setAttribute("hidden", "");
      viewerContent.id = "textview-content";
      viewerFilename.id = "viewerFilename";
      viewerWrapper.appendChild(viewerContent);
      viewerWrapper.appendChild(viewerFilename);
      doc.body.appendChild(viewerWrapper);
    }
    return { viewerWrapper, viewerContent, viewerFilename };
  }

  ({ viewerWrapper, viewerContent, viewerFilename } = createTextViewer());

  function displayWithLineNumbers(text) {
    const lines = text.split("\n");
    let htmlString = "<ol>";
    lines.forEach((line, index) => {
      const trimmedLine = line.trim();
      htmlString += `<li>${trimmedLine}</li>`;
    });
    htmlString += "</ol>";
    return htmlString;
  }

  function addListeners() {
    if (listenersActive) {return;}
    snarkFileNameLinks.forEach((link) => {
      link.addEventListener("click", function (event) {
        event.preventDefault();
        if (!doc.body.classList.contains("textviewer")) {doc.body.classList.add("textviewer");}
        if (isIframed && !parentDoc.body.classList.contains("textviewer")) {parentDoc.body.classList.add("textviewer");}
        if (isIframed && !doc.getElementById("textviewCss")) { loadIframeCSS(); }
        viewerFilename.textContent = "";
        viewerContent.scrollTo(0,0);
        const fileName = decodeURIComponent(link.href.split("/").pop());
        const lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex !== -1) {
          const fileExt = fileName.substring(lastDotIndex + 1).toLowerCase();
          if (fileExt !== "txt" && fileExt !== "srt") { viewerContent.classList.add("pre"); }
          if (supportedFileTypes.includes(fileExt)) {
            fetch(link.href).then((response) => response.text()).then((data) => {
              const parser = new DOMParser();
              const requestDoc = parser.parseFromString(data, "text/html");
              const escaped = requestDoc.documentElement.textContent || data;
              const numbered = numberedFileExts.includes(fileExt);
              viewerContent.innerHTML = numbered ? displayWithLineNumbers(escaped) : escaped;
              if (numbered) {viewerContent.classList.add("lines");}
              viewerFilename.textContent = fileName;
              viewerContent.appendChild(viewerFilename);
              viewerWrapper.removeAttribute("hidden");
            }).catch((error) => {});
          }
        }
      });
    });

    viewerContent.addEventListener("click", function (event) { event.stopPropagation(); });
    viewerWrapper.addEventListener("click", function () {
      viewerWrapper.setAttribute("hidden", "");
      doc.body.classList.remove("textviewer");
      if (isIframed) {
        parentDoc.body.classList.remove("textviewer");
        parentDoc.getElementById("textviewCss").remove();
      }
    });
    listenersActive = true;
  }
  addListeners();

});