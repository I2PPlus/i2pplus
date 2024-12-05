/* I2P+ textView.js for I2PSnark by dr|z3d */
/* Inline text viewer */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {

  const viewLinks = [
    'td.fileIcon.text>a',
    'td.snarkFileName>a[href$=".css"]',
    'td.snarkFileName>a[href$=".js"]',
    'td.snarkFileName>a[href$=".json"]',
    'td.snarkFileName>a[href$=".nfo"]',
    'td.snarkFileName>a[href$=".sh"]',
    'td.snarkFileName>a[href$=".srt"]',
    'td.snarkFileName>a[href$=".txt"]'
  ];

  const doc = document;
  const parentDoc = window.parent.document;
  const isIframed = doc.documentElement.classList.contains("iframed") || window.parent;
  const snarkFileNameLinks = doc.querySelectorAll(":where(" + viewLinks.join(",") + ")");
  const supportedFileTypes = new Set(["css", "csv", "js", "json", "nfo", "txt", "sh", "srt"]);
  const numberedFileExts = new Set(["css", "js", "sh"]);
  const cssHref = "/i2psnark/.res/textView.css";
  const textviewContent = doc.getElementById("textview-content");
  const responseCache = new Map();
  const parser = new DOMParser();
  let listenersActive = false, viewerWrapper, viewerContent, viewerFilename;

  const fragment = doc.createDocumentFragment();
  snarkFileNameLinks.forEach(link => {
    if (link.closest("td.snarkFileName")) {
      const newTabSpan = doc.createElement("span");
      newTabSpan.className = "newtab";
      const newTabLink = doc.createElement("a");
      newTabLink.href = link.href;
      newTabLink.target = "_blank";
      newTabLink.title = "Open in new tab";
      newTabSpan.appendChild(newTabLink);
      fragment.appendChild(newTabSpan);
      link.parentNode.insertBefore(newTabSpan, link.nextSibling);
    }
  });

  function loadCSS(href) {
    const snarkTheme = doc.getElementById("snarkTheme");
    const css = doc.createElement("link");
    css.rel = "stylesheet";
    css.href = href;
    snarkTheme.parentNode.insertBefore(css, snarkTheme);
  }

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

  const displayText = (link, fileName, fileExt, linkHref) => {
    if (responseCache.has(linkHref)) {
      renderContent(fileName, fileExt, responseCache.get(linkHref));
      return;
    }

    viewerFilename.innerHTML = "";
    const fileIconTd = link.closest("tr").querySelector("td.fileIcon");
    if (fileIconTd) {
      const fileIcon = fileIconTd.querySelector("img");
      if (fileIcon) { viewerFilename.prepend(fileIcon.cloneNode(true)); }
    }

    fetch(linkHref).then(response => response.text()).then(data => {
      responseCache.set(linkHref, data);
      renderContent(fileName, fileExt, data);
    }).catch(() => {});

    const fileNameSpan = doc.createElement("span");
    fileNameSpan.textContent = fileName;
    viewerFilename.appendChild(fileNameSpan);
  };

  const resetScrollPosition = () => {
    const txtContent = doc.getElementById("textview-content");
    if (txtContent && txtContent.innerHTML !== "") { txtContent.scrollTop = 0; }
    else { setTimeout(resetScrollPosition, 100); }
  }

  function preventScroll(selectors, prevent) {
    if (typeof selectors === "string") { selectors = [selectors]; }
    selectors.forEach(selector => {
      const elements = document.querySelectorAll(selector);
      const handleScroll = (event) => { event.preventDefault(); };
      elements.forEach(element => {
        if (prevent) {element.addEventListener("scroll", handleScroll);}
        else {element.removeEventListener("scroll", handleScroll);}
      });
    });
  }

  const renderContent = (fileName, fileExt, data) => {
    if (fileExt !== "txt" && fileExt !== "srt") { viewerContent.classList.add("pre"); }
    const escaped = parser.parseFromString(data, "text/html").body.textContent || data;
    const needsHardSpaces = numberedFileExts.has(fileExt) || viewerContent.classList.contains("pre");
    const encoded = escaped.replace(/ /g, needsHardSpaces ? " " : " ");
    viewerContent.innerHTML = numberedFileExts.has(fileExt) ? displayWithLineNumbers(encoded) : encoded;
    viewerContent.classList.toggle("lines", numberedFileExts.has(fileExt));
    viewerContent.insertBefore(viewerFilename, viewerContent.firstChild);
    viewerWrapper.hidden = false;
    requestAnimationFrame(resetScrollPosition);
    preventScroll("document.documentElement, document.body", true);
  };

  const addListeners = () => {
    if (listenersActive) return;
    snarkFileNameLinks.forEach(link => {
      link.addEventListener("click", event => {
        if (event.target.classList.contains("newtab")) return;
        event.preventDefault();
        const fileIconLink = link.closest("tr").querySelector("td.fileIcon > a");
        if (fileIconLink) {
          doc.documentElement.classList.add("textviewer");
          if (isIframed && !parentDoc.documentElement.classList.contains("fullscreen")) {
            parentDoc.documentElement.classList.add("textviewer", "fullscreen");
          }
          const fileName = decodeURIComponent(fileIconLink.href.split("/").pop());
          const fileExt = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
          if (supportedFileTypes.has(fileExt)) { displayText(fileIconLink, fileName, fileExt, fileIconLink.href); }
        }
      });
    });

    viewerContent.addEventListener("click", event => event.stopPropagation());
    viewerWrapper.addEventListener("click", () => {
      viewerWrapper.hidden = true;
      doc.documentElement.classList.remove("textviewer", "fullscreen");
      preventScroll("document.documentElement, document.body", false);
      if (isIframed) {
        parentDoc.documentElement.classList.remove("textviewer", "fullscreen");
        preventScroll("window.parent.document.documentElement, window.parent.document.body", false);
      }
    });
    listenersActive = true;
  };

  addListeners();

});