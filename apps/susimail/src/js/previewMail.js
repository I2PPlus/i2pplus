/**
 * @module previewMail
 * @file SusiMail message preview on hover.
 * Displays a floating preview popup when hovering over mail subject links
 * in the folder list, with caching, timeout handling, and theme-aware styling.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Sets up the preview-popup system. Wires up mouseenter/mouseleave
 * event delegation on the document, creates the popup element, and
 * manages fetch caching for message previews.
 * @function previewMail
 * @returns {void}
 */
function previewMail() {
  const PREVIEW_DELAY_MS = 150;
  const PREVIEW_HIDE_DELAY_MS = 250;
  const FETCH_TIMEOUT_MS = 3000;
  const MAX_PREVIEW_SIZE = 300000;
  const previewCache = new Map();
  let hoverTimer = null;
  let hideTimer = null;
  let popup = null;
  let currentPreviewUrl = null;

  /**
   * Returns the preview text for the given URL, using an in-memory cache
   * and deduplicating concurrent requests for the same resource.
   *
   * @async
   * @function fetchPreview
   * @param {string} showValue - The URL to fetch the mail preview from.
   * @param {number} [depth=0] - Current recursion depth (max 1) for iframe/attachment follow.
   * @returns {Promise<string>} The cleaned preview text, or empty string on failure.
   */
  async function fetchPreview(showValue, depth = 0) {
    const cacheKey = `${depth}:${showValue}`;
    if (previewCache.has(cacheKey)) {
      const cached = previewCache.get(cacheKey);
      if (typeof cached === "string") return cached;
      if (cached instanceof Promise) {
        try {
          const result = await cached;
          return result || "";
        } catch {
          previewCache.delete(cacheKey);
          return "";
        }
      }
    }

    const fetchPromise = _doFetchPreview(showValue, depth);
    previewCache.set(cacheKey, fetchPromise);

    try {
      const result = await fetchPromise;
      previewCache.set(cacheKey, result);
      return result;
    } catch (err) {
      previewCache.delete(cacheKey);
      return "";
    }
  }

  /**
   * Performs the actual fetch and parses the response. Handles binary
   * content detection, size limits, iframe/attachment recursion, and
   * extracts mailbody paragraph text.
   *
   * @async
   * @function _doFetchPreview
   * @param {string} showValue - The URL to fetch.
   * @param {number} [depth=0] - Recursion depth; stops at 1.
   * @returns {Promise<string>} Preview text or status message string.
   */
  async function _doFetchPreview(showValue, depth = 0) {
    if (depth > 1) return "";

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);

    let response;
    try {
      response = await fetch(showValue, {
        credentials: "same-origin",
        headers: { Accept: "text/html" },
        signal: controller.signal
      });
    } catch (e) {
      clearTimeout(timeoutId);
      if (e.name === "AbortError") {
        return "Could not preview mail (not downloaded?)";
      }
      return "Network error loading preview.";
    }

    clearTimeout(timeoutId);

    if (!response.ok) return "";

    const contentType = (response.headers.get("content-type") || "").toLowerCase();
    if (contentType.includes("image/") ||
        contentType.includes("application/pdf") ||
        contentType.includes("application/zip") ||
        contentType.includes("application/x-rar-compressed") ||
        contentType.includes("application/x-7z-compressed") ||
        contentType.includes("application/octet-stream") ||
        contentType.includes("application/msword") ||
        contentType.includes("application/vnd.openxmlformats-officedocument")) {
      return `Binary file attachment detected: ${contentType.split(";")[0]}.`;
    }

    const contentLength = response.headers.get("content-length");
    if (contentLength && parseInt(contentLength, 10) > MAX_PREVIEW_SIZE) {
      return "Preview skipped: message too large.";
    }

    let text;
    try {
      text = await response.text();
    } catch (e) {
      return "Failed to read message content.";
    }

    if (text.length === 0) return "";
    if (text.length > MAX_PREVIEW_SIZE) {
      return "Preview skipped: message too large.";
    }

    await new Promise(resolve => setTimeout(resolve, 0));

    const doc = new DOMParser().parseFromString(text, "text/html");

    const mailbodyParagraphs = Array.from(doc.querySelectorAll("p.mailbody"))
      .map(p => p.textContent.trim())
      .filter(t => t.length > 0);

    if (mailbodyParagraphs.length > 0) {
      return cleanText(mailbodyParagraphs.join("\n\n"));
    }

    const iframe = doc.querySelector('iframe[src*="?att="][src*="msg="]');
    if (iframe?.src) {
      const iframeUrl = new URL(iframe.src, window.location.origin).href;
      return await fetchPreview(iframeUrl, depth + 1);
    }

    const attLink = doc.querySelector('a[href*="?att="][href*="msg="]');
    if (attLink?.href) {
      const attUrl = new URL(attLink.href, window.location.origin).href;
      return await fetchPreview(attUrl, depth + 1);
    }

    return cleanText(doc.body?.textContent || "") || "";
  }

  /**
   * Normalises raw extracted text by collapsing whitespace, removing
   * boilerplate "view online" lines, and stripping residual HTML tags.
   *
   * @function cleanText
   * @param {string} raw - Unprocessed text content.
   * @returns {string} Cleaned and trimmed text.
   */
  function cleanText(raw) {
    if (!raw) return "";
    raw = raw.replace(/Having[\s\u00A0\u2000-\u200B\u3000]+problems[\s\u00A0\u2000-\u200B\u3000]+viewing[\s\u00A0\u2000-\u200B\u3000]+this[\s\u00A0\u2000-\u200B\u3000]+email\?View[\s\u00A0\u2000-\u200B\u3000]+email[\s\u00A0\u2000-\u200B\u3000]+online/gi, "");
    raw = raw.replace(/[\s\u00A0\u2000-\u200B\u3000]+/g, " ");
    raw = raw.replace(/,(?=\S)/g, ", ");
    raw = raw.replace(/<([a-zA-Z][\w-]*)[^>]*>(.*?)<\/\1>/gi, "$2");
    raw = raw.replace(/<([a-zA-Z][\w-]*)\b[^>]*\/>/g, "");
    return raw.trim();
  }

  /**
   * Lazily creates the preview popup `<div>` element with theme-aware
   * styling and hover-persistence listeners, appending it to the document body.
   *
   * @function ensurePopup
   * @returns {HTMLElement} The popup element.
   */
  function ensurePopup() {
    if (popup) return popup;
    const htmlTag = document.documentElement;
    popup = document.createElement("div");
    popup.id = "mail-preview-popup";
    let popupBg = "#000";
    let popupBorder = "#555";
    if (
      (window.theme && ["light", "classic"].includes(window.theme)) ||
      htmlTag.classList.contains("light") ||
      htmlTag.classList.contains("classic")
    ) {
      popupBg = "#f2f2f2";
      popupBorder = "2px solid #bbc";
    }

    Object.assign(popup.style, {
      padding: "10px 16px",
      maxWidth: "70%",
      minWidth: "480px",
      display: "none",
      position: "absolute",
      zIndex: "9999",
      wordBreak: "break-word",
      border: popupBorder,
      borderRadius: "2px",
      background: popupBg,
      boxShadow: "0 2px 10px rgba(0,0,0,0.3)",
    });

    const inner = document.createElement("div");
    inner.className = "mail-preview-content";
    inner.textContent = "Loading…";
    Object.assign(inner.style, {
      padding: "4px 8px",
      maxHeight: "300px",
      display: "inline-block",
      overflow: "auto"
    });

    popup.appendChild(inner);
    document.body.appendChild(popup);

    popup.addEventListener("mouseenter", () => {
      clearTimeout(hideTimer);
    });
    popup.addEventListener("mouseleave", () => {
      hideTimer = setTimeout(() => hidePopup(), PREVIEW_HIDE_DELAY_MS);
    });

    return popup;
  }

  /**
   * Positions the popup element relative to the target anchor, preferring
   * below but flipping above if space is insufficient, and clamping to
   * the viewport width.
   *
   * @function positionPopup
   * @param {HTMLElement} targetEl - The element to position the popup near.
   * @returns {void}
   */
  function positionPopup(targetEl) {
    const rect = targetEl.getBoundingClientRect();
    const popupRect = popup.getBoundingClientRect();

    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;

    let top;
    if (spaceBelow >= popupRect.height + 16) {
      top = window.scrollY + rect.bottom + 8;
    } else if (spaceAbove >= popupRect.height + 16) {
      top = window.scrollY + rect.top - popupRect.height - 8;
    } else {
      top = window.scrollY + rect.bottom + 8;
    }

    let left = window.scrollX + rect.left;
    if (left + popupRect.width > window.innerWidth) {
      left = window.scrollX + Math.max(0, window.innerWidth - popupRect.width - 16);
    }

    popup.style.top = `${top}px`;
    popup.style.left = `${left}px`;
  }

  /**
   * Displays the preview popup anchored to the given element, fetches
   * the preview content, and updates the popup text once loaded.
   *
   * @async
   * @function showPopup
   * @param {HTMLElement} el - The anchor element triggering the preview.
   * @param {string} showValue - The URL of the mail to preview.
   * @returns {Promise<void>}
   */
  async function showPopup(el, showValue) {
    const p = ensurePopup();
    const content = p.querySelector(".mail-preview-content");

    clearTimeout(hideTimer);
    clearTimeout(hoverTimer);

    content.textContent = "Loading…";
    content.classList.add("fetching");
    currentPreviewUrl = showValue;
    positionPopup(el);
    popup.style.display = "block";

    const preview = await fetchPreview(showValue);
    if (currentPreviewUrl === showValue) {
      if (preview) {
        content.textContent = preview;
      } else {
        content.textContent = "No preview available";
      }
      content.classList.remove("fetching");
    }
  }

  /**
   * Hides the preview popup if it exists.
   *
   * @function hidePopup
   * @returns {void}
   */
  function hidePopup() {
    if (popup) popup.style.display = "none";
  }

  document.documentElement.addEventListener("mouseenter", (e) => {
    const anchor = e.target.closest(".mailListSubject a");
    if (!anchor) return;
    const showValue = anchor.getAttribute("href");
    if (!showValue) return;
    clearTimeout(hideTimer);
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => showPopup(anchor, showValue), PREVIEW_DELAY_MS);
  }, true);

  document.documentElement.addEventListener("mouseleave", (e) => {
    const anchor = e.target.closest(".mailListSubject a");
    if (!anchor) return;
    clearTimeout(hoverTimer);
    hideTimer = setTimeout(() => hidePopup(), PREVIEW_HIDE_DELAY_MS);
  }, true);

  ensurePopup();
}

document.addEventListener("DOMContentLoaded", previewMail);