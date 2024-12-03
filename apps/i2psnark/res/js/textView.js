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
    'td.snarkFileName>a[href$=".txt"]'
  ];

  const iframedStyles = ".textviewer,.textviewer body,.textviewer #torrents.main,.textviewer #i2psnarkframe{margin:0!important;padding:0!important;width:100%;height:100%!important;height:100vh!important;position:absolute;top:0;left:0;bottom:0;right:0;overflow:hidden;border:0!important;contain:paint}.textviewer h1{display:none}";

  const doc = document;
  const parentDoc = window.parent.document;
  const isIframed = doc.documentElement.classList.contains("iframed") || window.parent;
  const snarkFileNameLinks = doc.querySelectorAll(":where(" + viewLinks.slice(1).join(",") + ")");
  const supportedFileTypes = new Set(["css", "csv", "js", "json", "nfo", "txt", "sh", "srt"]);
  const numberedFileExts = new Set(["css", "js", "sh"]);
  const cssHref = "/i2psnark/.res/textView.css";
  const textviewContent = document.getElementById("textview-content");

  const responseCache = new Map();
  let listenersActive = false, viewerWrapper, viewerContent, viewerFilename;

  const fragment = doc.createDocumentFragment();
  snarkFileNameLinks.forEach(link => {
    const newTabSpan = doc.createElement("span");
    newTabSpan.className = "newtab";
    const newTabLink = doc.createElement("a");
    newTabLink.href = link.href;
    newTabLink.target = "_blank";
    newTabLink.title = "Open in new tab";
    newTabSpan.appendChild(newTabLink);
    fragment.appendChild(newTabSpan);
    link.parentNode.insertBefore(newTabSpan, link.nextSibling);
  });

  function loadCSS(href) {
    const snarkTheme = doc.getElementById("snarkTheme");
    const css = doc.createElement("link");
    css.rel = "stylesheet";
    css.href = href;
    snarkTheme.parentNode.insertBefore(css, snarkTheme);
  }

  const loadIframeCSS = () => {
    if (!isIframed) return;
    const cssIframed = doc.createElement("style");
    cssIframed.id = "textviewCss";
    cssIframed.innerHTML = iframedStyles;
    parentDoc.body.appendChild(cssIframed);
  };

  function createTextViewer() {
    if (!viewerWrapper) {
      loadCSS(cssHref);
      viewerWrapper = doc.createElement("div");
      viewerContent = doc.createElement("div");
      viewerFilename = doc.createElement("div");
      viewerWrapper.id = "textview";
      viewerWrapper.hidden = true;
      viewerContent.id = "textview-content";
      viewerFilename.id = "viewerFilename";
      viewerContent.appendChild(viewerFilename);
      viewerWrapper.appendChild(viewerContent);
      doc.body.appendChild(viewerWrapper);
      return { viewerWrapper, viewerContent, viewerFilename };
    }
  }

  ({ viewerWrapper, viewerContent, viewerFilename } = createTextViewer());

  const displayWithLineNumbers = text => {
    const lines = text.split("\n").map(line => `<li>${line}</li>`);
    return `<ol>${lines.join("")}</ol>`;
  };

  const displayText = (fileName, fileExt, linkHref) => {
    if (responseCache.has(linkHref)) {
      renderContent(fileName, fileExt, responseCache.get(linkHref));
      return;
    }

    fetch(linkHref)
      .then(response => response.text())
      .then(data => {
        responseCache.set(linkHref, data);
        viewerFilename.textContent = fileName;
        renderContent(fileName, fileExt, data);
      })
      .catch(() => {});
  };

  const resetScrollPosition = () => {
    const txtContent = document.getElementById("textview-content");
    if (txtContent && txtContent.innerHTML !== "") { txtContent.scrollTop = 0; }
    else { setTimeout(resetScrollPosition, 100); }
  }

  const renderContent = (fileName, fileExt, data) => {
    if (fileExt !== "txt" && fileExt !== "srt") { viewerContent.classList.add("pre"); }
    const escaped = new DOMParser().parseFromString(data, "text/html").documentElement.textContent || data;
    const needsHardSpaces = numberedFileExts.has(fileExt) || viewerContent.classList.contains("pre");
    const encoded = escaped.replace(/ /g, needsHardSpaces ? " " : " ");
    viewerContent.innerHTML = numberedFileExts.has(fileExt) ? displayWithLineNumbers(encoded) : encoded;
    viewerContent.classList.toggle("lines", numberedFileExts.has(fileExt));
    viewerFilename.textContent = fileName;
    viewerContent.insertBefore(viewerFilename, viewerContent.firstChild);
    viewerWrapper.hidden = false;
    requestAnimationFrame(resetScrollPosition);
  };

  const addListeners = () => {
    if (listenersActive) return;
    snarkFileNameLinks.forEach(link => {
      link.addEventListener("click", event => {
        event.preventDefault();
        doc.documentElement.classList.add("textviewer");
        if (isIframed && !parentDoc.documentElement.classList.contains("textviewer")) {
          parentDoc.documentElement.classList.add("textviewer");
          if (!doc.getElementById("textviewCss")) loadIframeCSS();
        }
        viewerFilename.textContent = "";
        const fileName = decodeURIComponent(link.href.split("/").pop());
        const fileExt = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        if (supportedFileTypes.has(fileExt)) displayText(fileName, fileExt, link.href);
      });
    });

    viewerContent.addEventListener("click", event => event.stopPropagation());
    viewerWrapper.addEventListener("click", () => {
      viewerWrapper.hidden = true;
      doc.documentElement.classList.remove("textviewer");
      if (isIframed) {
        parentDoc.documentElement.classList.remove("textviewer");
        const textviewCss = parentDoc.getElementById("textviewCss");
        if (textviewCss) textviewCss.remove();
      }
    });
    listenersActive = true;
  };

  addListeners();

});