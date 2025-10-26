/* SusiMail previewMail.js by dr|z3d */
/* Display message preview on mail list subject mouseover */
/* License: AGPL3 or later */

function previewMail() {
  const PREVIEW_DELAY_MS = 150;
  const PREVIEW_HIDE_DELAY_MS = 250;
  const previewCache = new Map(); // In-memory, session-only cache
  let hoverTimer = null;
  let hideTimer = null;
  let popup = null;

  // Caching wrapper with deduplication
  async function fetchPreview(showValue, depth = 0) {
    const cacheKey = `${depth}:${showValue}`;
    if (previewCache.has(cacheKey)) {
      const cached = previewCache.get(cacheKey);
      if (typeof cached === 'string') return cached;
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
      console.warn("Preview fetch failed:", showValue, err);
      return "";
    }
  }

  async function _doFetchPreview(showValue, depth = 0) {
    if (depth > 1) return "";

    let response;
    try {
      response = await fetch(showValue, {
        credentials: "same-origin",
        headers: { Accept: "text/html"
        }
      });
    } catch (e) {
      return "Network error loading preview.";
    }

    if (!response.ok) return "";

    // Try to detect binary content
    const buffer = await response.arrayBuffer();
    const signatureMsg = checkFileSignature(buffer);
    if (signatureMsg) return signatureMsg;

    // If not binary, parse as text
    const decoder = new TextDecoder("utf-8");
    const text = decoder.decode(buffer);
    const doc = new DOMParser().parseFromString(text, "text/html");

    // Webmail UI page: try plaintext first
    const mailbodyParagraphs = Array.from(doc.querySelectorAll("p.mailbody"))
      .map(p => p.textContent.trim())
      .filter(text => text.length > 0);

    if (mailbodyParagraphs.length > 0) {
      return cleanText(mailbodyParagraphs.join("\n\n"));
    }

    // Try iframe attachment
    const iframe = doc.querySelector('iframe[src*="?att="][src*="msg="]');
    if (iframe?.src) {
      const iframeUrl = new URL(iframe.src, window.location.origin).href;
      return await fetchPreview(iframeUrl, depth + 1);
    }

    // Fallback: <a> attachment link
    const attLink = doc.querySelector('a[href*="?att="][href*="msg="]');
    if (attLink?.href) {
      const attUrl = new URL(attLink.href, window.location.origin).href;
      return await fetchPreview(attUrl, depth + 1);
    }

    // Last resort: body text from UI page
    return cleanText(doc.body?.textContent || "") || "";
  }

  function cleanText(raw) {
    if (!raw) return "";
    // Remove "Having problems viewing..." banner (Unicode-safe)
    raw = raw.replace(/Having[\s\u00A0\u2000-\u200B\u3000]+problems[\s\u00A0\u2000-\u200B\u3000]+viewing[\s\u00A0\u2000-\u200B\u3000]+this[\s\u00A0\u2000-\u200B\u3000]+email\?View[\s\u00A0\u2000-\u200B\u3000]+email[\s\u00A0\u2000-\u200B\u3000]+online/gi, "");
    // Normalize all whitespace including newlines to single space
    raw = raw.replace(/[\s\u00A0\u2000-\u200B\u3000]+/g, " ");
    // Fix commas
    raw = raw.replace(/,(?=\S)/g, ", ");
    // Remove specific paired tags but keep their inner content
    raw = raw.replace(/<([a-zA-Z][\w-]*)[^>]*>(.*?)<\/\1>/gi, "$2");
    // Remove self-closing tags
    raw = raw.replace(/<([a-zA-Z][\w-]*)\b[^>]*\/>/g, "");
    return raw.trim();
  }

  function checkFileSignature(buffer) {
    const signatures = [
      { signature: [0x50, 0x4B, 0x03, 0x04], type: "ZIP archive" },
      { signature: [0x25, 0x50, 0x44, 0x46], type: "PDF document" },
      { signature: [0xFF, 0xD8, 0xFF], type: "JPEG image" },
      { signature: [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A], type: "PNG image" },
      { signature: [0x47, 0x49, 0x46, 0x38, 0x37, 0x61], type: "GIF image (87a)" },
      { signature: [0x47, 0x49, 0x46, 0x38, 0x39, 0x61], type: "GIF image (89a)" },
      { signature: [0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00], type: "RAR archive" },
      { signature: [0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C], type: "7-Zip archive" },
    ];

    const view = new Uint8Array(buffer);
    for (const { signature, type } of signatures) {
      if (view.length >= signature.length) {
        let match = true;
        for (let i = 0; i < signature.length; i++) {
          if (view[i] !== signature[i]) {
            match = false;
            break;
          }
        }
        if (match) return `Binary file attachment detected: ${type}.`;
      }
    }
    return null;
  }

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
      top = window.scrollY + rect.bottom + 8; // fallback
    }

    let left = window.scrollX + rect.left;
    if (left + popupRect.width > window.innerWidth) {
      left = window.scrollX + Math.max(0, window.innerWidth - popupRect.width - 16);
    }

    popup.style.top = `${top}px`;
    popup.style.left = `${left}px`;
  }

  async function showPopup(el, showValue) {
    const p = ensurePopup();
    const content = p.querySelector(".mail-preview-content");

    clearTimeout(hideTimer);
    clearTimeout(hoverTimer);

    content.textContent = "Loading…";
    content.classList.add("fetching");
    positionPopup(el);
    popup.style.display = "block";

    const preview = await fetchPreview(showValue);
    if (preview) {
      content.textContent = preview;
    } else {
      content.textContent = "No preview available";
    }

    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        positionPopup(el);
      });
    });
  }

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