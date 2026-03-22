/**
 * @module textView
 * @file textView.js - Inline text viewer for I2PSnark file browser.
 * @description Provides an inline text viewing overlay for supported text file types
 * (.asc, .bat, .css, .ini, .js, .json, .md5, .nfo, .sh, .srt, .txt, .url, and CSV).
 * Supports line-numbered display for code-like files, iframe-aware fullscreen mode,
 * response caching, scroll locking, and open-in-new-tab links.
 * @author dr|z3d
 * @license AGPL3 or later
 */

document.addEventListener("DOMContentLoaded", function () {

  /**
   * @type {string[]}
   * @description CSS selectors for links to supported text file types. Used to identify
   * clickable elements that should open in the inline text viewer.
   */
  const viewLinks = [
    'td.fileIcon.text>a',
    'td.snarkFileName>a[href$=".asc"]',
    'td.snarkFileName>a[href$=".bat"]',
    'td.snarkFileName>a[href$=".css"]',
    'td.snarkFileName>a[href$=".ini"]',
    'td.snarkFileName>a[href$=".js"]',
    'td.snarkFileName>a[href$=".json"]',
    'td.snarkFileName>a[href$=".md5"]',
    'td.snarkFileName>a[href$=".nfo"]',
    'td.snarkFileName>a[href$=".sh"]',
    'td.snarkFileName>a[href$=".srt"]',
    'td.snarkFileName>a[href$=".txt"]',
    'td.snarkFileName>a[href$=".url"]'
  ];

  const doc = document;
  const parentDoc = window.parent.document;
  const isIframed = doc.documentElement.classList.contains("iframed") || window.parent;
  const snarkFileNameLinks = doc.querySelectorAll(":where(" + viewLinks.join(",") + ")");
  /**
   * @type {Set<string>}
   * @description Set of supported file extensions for the inline text viewer.
   */
  const supportedFileTypes = new Set(["asc", "bat", "css", "csv", "ini", "js", "json", "md5", "nfo", "txt", "sh", "srt", "url"]);

  /**
   * @type {Set<string>}
   * @description Set of file extensions that should display with line numbers.
   */
  const numberedFileExts = new Set(["bat", "css", "ini", "js", "sh"]);

  /**
   * @type {string}
   * @description URL path to the text viewer stylesheet.
   */
  const cssHref = "/i2psnark/.res/textView.css";

  /**
   * @type {?HTMLElement}
   * @description The text view content container element.
   */
  const textviewContent = doc.getElementById("textview-content");

  /**
   * @type {Map<string, string>}
   * @description Cache mapping fetched file URLs to their text content.
   */
  const responseCache = new Map();

  /**
   * @type {DOMParser}
   * @description Reusable DOMParser for escaping HTML in fetched content.
   */
  const parser = new DOMParser();

  /**
   * @type {boolean}
   * @description Whether event listeners have already been attached.
   */
  let listenersActive = false;

  /** @type {?HTMLElement} */
  let viewerWrapper;

  /** @type {?HTMLElement} */
  let viewerContent;

  /** @type {?HTMLElement} */
  let viewerFilename;

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

  /**
   * @function loadCSS
   * @description Dynamically loads a CSS stylesheet by inserting a <link> element before
   * the #snarkTheme element in the document head.
   * @param {string} href - The URL of the CSS file to load.
   * @returns {void}
   */
  function loadCSS(href) {
    const snarkTheme = doc.getElementById("snarkTheme");
    const css = doc.createElement("link");
    css.rel = "stylesheet";
    css.href = href;
    snarkTheme.parentNode.insertBefore(css, snarkTheme);
  }

  /**
   * @function createTextViewer
   * @description Creates the text viewer DOM structure (wrapper, content, filename elements)
   * if it doesn't already exist. Loads the viewer CSS and appends the wrapper to the body.
   * @returns {?Object} An object with viewerWrapper, viewerContent, and viewerFilename references, or undefined if already created.
   */
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

  /**
   * @function displayWithLineNumbers
   * @description Converts plain text into an ordered list with each line as a list item,
   * providing numbered line display for code-like files.
   * @param {string} text - The raw text content to format.
   * @returns {string} HTML string with line numbers as an ordered list.
   */
  const displayWithLineNumbers = text => {
    const lines = text.split("\n").map(line => `<li>${line}</li>`);
    return `<ol>${lines.join("")}</ol>`;
  };

  /**
   * @function displayText
   * @description Fetches and displays the content of a text file in the viewer. Uses the
   * response cache to avoid redundant fetches. Shows the file icon and filename in the viewer header.
   * @param {HTMLElement} link - The clicked file link element.
   * @param {string} fileName - The decoded filename to display.
   * @param {string} fileExt - The lowercase file extension.
   * @param {string} linkHref - The URL to fetch the file content from.
   * @returns {void}
   */
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

  /**
   * @function resetScrollPosition
   * @description Resets the text view content scroll position to the top. Retries after
   * a short delay if the content element is not yet available.
   * @returns {void}
   */
  const resetScrollPosition = () => {
    const txtContent = doc.getElementById("textview-content");
    if (txtContent && txtContent.innerHTML !== "") { txtContent.scrollTop = 0; }
    else { setTimeout(resetScrollPosition, 100); }
  }

  /**
   * @function preventScroll
   * @description Adds or removes scroll prevention handlers on elements matching the given
   * selectors. When active, prevents default scroll behavior (useful for locking body scroll
   * while the text viewer is open).
   * @param {string|string[]} selectors - CSS selector(s) for elements to lock/unlock scrolling.
   * @param {boolean} prevent - Whether to enable (true) or disable (false) scroll prevention.
   * @returns {void}
   */
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

  /**
   * @function renderContent
   * @description Renders fetched text content into the viewer. Applies pre-formatted styling
   * for non-plain-text files, HTML-escapes the content, displays with or without line numbers
   * based on file type, and shows the viewer wrapper.
   * @param {string} fileName - The filename to display in the viewer header.
   * @param {string} fileExt - The file extension determining display format.
   * @param {string} data - The raw text content to render.
   * @returns {void}
   */
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

  /**
   * @function addListeners
   * @description Attaches click handlers to file links for opening the text viewer, and
   * click handlers on the viewer for closing it. Prevents duplicate listener attachment.
   * @returns {void}
   */
  const addListeners = () => {
    if (listenersActive) { return; }
    snarkFileNameLinks.forEach(link => {
      link.addEventListener("click", event => {
        if (event.target.classList.contains("newtab")) { return; }
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