/**
 * @module mailFrame
 * @file SusiMail HTML message frame handler.
 *
 * Everything an HTML message needs that the message itself is not trusted to do:
 * normalising the inline styles the sender wrote, replacing blocked remote images
 * with a placeholder, counting what was blocked or removed, and sizing the frame to
 * its content.
 *
 * It runs in the page, never in the frame. The frame is sandboxed
 * (`sandbox="allow-same-origin"`, no `allow-scripts`) and its Content-Security-Policy
 * allows no script at all, so a message cannot execute; the page reaches in through
 * `contentDocument`, which the `allow-same-origin` permission is what makes possible.
 * The work this file does used to be done by a script injected into the message
 * document, which meant the only thing between a hostile message and the mail
 * session was the CSP script allowlist.
 *
 * @author dr|z3d
 * @license AGPL3 or later
 */

/** The id WebMail gives the message frame. */
export const FRAME_ID = "iframeSusiHtmlView";

/** The marker WebMail injects to say where a message's content ends. */
export const HEIGHT_MARKER = "[data-iframe-height]";

/**
 * The icon shown where a tracking image was taken out. Served from this origin, which
 * the frame's Content-Security-Policy allows for images.
 */
export const WEB_BUG_ICON = "/themes/susimail/images/webBug.svg";

/** The size the tracking placeholder is drawn at, in CSS pixels. */
const WEB_BUG_SIZE = 32;

/**
 * The inline icon shown in place of a remote image the policy blocked. Inlined as a
 * data: url because the frame's CSP allows images only from the mail origin and data:.
 */
export const BLOCKED_ICON =
  "data:image/svg+xml," +
  "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E" +
  "%3Cg transform='translate(-5.33 -5.33)'%3E" +
  "%3C" +
  "path fill='%2374bbf0' d='" +
  "M52.14 8H11.86C9.71 8 8 10.05 8 12.54v38.91C8 54.03 9.71 56 11.86 56h40.29c2.14 0 3." +
  "86-2.06 3.86-4.54V12.54C56 9.97 54.29 8 52.14 8z'/%3E" +
  "%3C" +
  "path fill='%23fff' d='" +
  "M8 29.17a13.76 13.76 0 0 1 6.17-1.46c4.46 0 8.14 1.89 9.43 4.46 1.76-.62 3.62-.91 5." +
  "49-.86 5.49 0 10.03 2.57 10.63 6.09 4.71.34 8.4 2.91 8.57 6l-11.57 7.46H8z'/%3E" +
  "%3C" +
  "path fill='%236c994e' d='" +
  "M21.2 45.54S13.49 43.4 8 48.28v5.57C8 55.14 9.29 56 10.91 56l11.57-6.6z'/%3E" +
  "%3C" +
  "path d='" +
  "M8 51.11c5.57-4.8 13.2-2.66 13.2-2.66l.43 1.37.86-.43-1.29-3.86S13.49 43.4 8 48.28z'" +
  " opacity='.1'/%3E" +
  "%3C" +
  "path fill='%237eb35b' d='" +
  "M10.91 56c3.86-7.71 15.94-16.89 26.06-11.74l-3.43 8.14-8.4 3.6z'/%3E" +
  "%3C" +
  "path d='" +
  "m36.63 45.11.34-.86C26.86 39.11 14.77 48.28 10.91 56h3.69c4.29-6.43 13.46-13.03 22.0" +
  "3-10.89z' opacity='.1'/%3E" +
  "%3C" +
  "path fill='%23a5eb78' d='" +
  "M56 53.77V39.11C36.63 37.65 25.14 56 25.14 56h27.94c1.63 0 2.91-.94 2.91-2.23z'/%3E" +
  "%3C" +
  "path d='M56 41v-1.89C36.63 37.65 25.14 56 25.14 56H29c3.43-4.37 12.94-14.83 27-15z'" +
  " opacity='.1'/%3E%3Ccircle cx='44.69' cy='19.31' r='8.57' fill='%23ffe97a'/%3E" +
  "%3C" +
  "path fill='red' d='" +
  "M32 12.51a19.49 19.49 0 1 0 0 38.98 19.49 19.49 0 1 0 0-38.98zm0 4.87A14.62 14.62 0 " +
  "0 1 46.62 32a14.62 14.62 0 0 1-2.44 7.92l-20.1-20.1A14.62 14.62 0 0 1 32 17.38zm-12." +
  "18 6.7 20.1 20.1A14.62 14.62 0 0 1 32 46.62 14.62 14.62 0 0 1 17.38 32a14.62 14.62 0" +
  " 0 1 2.44-7.92z'/%3E%3C/g%3E%3C/svg%3E";

/**
 * The document inside the message frame, or null when it cannot be read.
 *
 * A sandboxed frame that has navigated away, or one the browser has made opaque,
 * throws on this access rather than reporting nothing, so the caller is left with a
 * frame it can only size by other means.
 *
 * @param {Element} frame the message iframe
 * @returns {?Document} the frame's document, null if unreachable
 */
export function frameDocument(frame) {
  if (!frame) {return null;}
  try {
    return frame.contentDocument;
  } catch (err) {
    return null;
  }
}

/**
 * The style a message element should keep.
 *
 * A sender's `!important` outranks everything the reader has set for their own
 * display, and a `height: 100%` on an element inside a frame that is sized to its
 * content collapses the layout, so both are taken out. Everything else the sender
 * wrote is left alone.
 *
 * @param {string} style the element's inline style text
 * @returns {string} the style to write back, "" when nothing is left
 */
export function normalizeStyle(style) {
  if (!style) {return "";}
  let out = style;
  if (out.toLowerCase().includes("height") && out.includes("100%")) {
    // anchored at a declaration start, so max-height and line-height are left alone
    out = out.replace(/(^|;)\s*height\s*:\s*[^;]*100%[^;]*;?/gi, "$1");
  }
  if (out.toLowerCase().includes("!important")) {
    out = out.replace(/!important/gi, "");
  }
  return out.trim();
}

/**
 * Rewrites the styles of every element in the message.
 *
 * @param {Document} doc the frame's document
 * @returns {void}
 */
export function normalizeStyles(doc) {
  if (!doc || !doc.documentElement) {return;}
  doc.querySelectorAll("*").forEach(function(element) {
    const style = element.getAttribute("style");
    if (style) {
      const kept = normalizeStyle(style);
      if (kept) {element.setAttribute("style", kept);}
      else {element.removeAttribute("style");}
    }
    // a centred column reads better in a frame of the reader's width than a flush one
    if (element.tagName && element.tagName.toLowerCase() === "div" &&
        typeof getComputedStyle === "function") {
      const margin = getComputedStyle(element).getPropertyValue("margin");
      if (margin === "0px") {element.style.margin = "auto";}
    }
  });
  doc.documentElement.style.background = "#fff";
}

/**
 * The images the policy blocked, whether or not they are being shown.
 *
 * @param {Document} doc the frame's document
 * @returns {Element[]} the blocked images
 */
export function blockedImages(doc) {
  if (!doc) {return [];}
  return Array.from(doc.querySelectorAll("img[data-src-blocked]"));
}

/**
 * The placeholders left where a tracking pixel was removed.
 *
 * @param {Document} doc the frame's document
 * @returns {Element[]} the web-bug placeholders
 */
export function webBugs(doc) {
  if (!doc) {return [];}
  return Array.from(doc.querySelectorAll(".webBug"));
}

/**
 * Puts the placeholder icon on every blocked image and remembers the URL it stands
 * for in the title, so the reader can see what would have loaded.
 *
 * @param {Document} doc the frame's document
 * @returns {void}
 */
export function markBlockedImages(doc) {
  blockedImages(doc).forEach(function(image) {
    const blockedURL = image.getAttribute("data-src-blocked");
    if (blockedURL !== null) {image.setAttribute("title", blockedURL);}
    image.setAttribute("src", BLOCKED_ICON);
  });
}

/**
 * Gives a tracking placeholder the box and icon it needs to be seen at all - the
 * server leaves it an empty, hidden span.
 *
 * @param {Element} bug the placeholder element
 * @returns {void}
 */
export function styleWebBug(bug) {
  bug.style.background = "url(" + WEB_BUG_ICON + ") no-repeat center center/" + WEB_BUG_SIZE + "px";
  bug.style.width = WEB_BUG_SIZE + "px";
  bug.style.height = WEB_BUG_SIZE + "px";
}

/**
 * Hides or shows the frame's images and tracking placeholders.
 *
 * @param {Element[]} images the elements to act on
 * @param {boolean} hidden true to hide them, false to show them
 * @returns {void}
 */
export function setHidden(images, hidden) {
  images.forEach(function(image) {
    if (hidden) {
      image.style.display = "none";
      image.setAttribute("hidden", "hidden");
    } else {
      if (image.classList && image.classList.contains("webBug")) {styleWebBug(image);}
      image.style.display = "inline-block";
      image.removeAttribute("hidden");
    }
  });
}

/**
 * Reports in the page how much of the message the policy removed or blocked.
 *
 * The bar and each of its two tallies are hidden until there is something to report,
 * and the two tallies are separated by a bullet only when both are present.
 *
 * @param {Document} parent the page's document
 * @param {Element[]} blocked the blocked images
 * @param {Element[]} bugs the web-bug placeholders
 * @returns {void}
 */
export function showCounts(parent, blocked, bugs) {
  const bar = parent.getElementById("blockedImages");
  if (!bar) {return;}
  if (blocked.length > 0) {
    bar.removeAttribute("hidden");
    const line = parent.getElementById("blockedImgLine");
    if (line) {line.removeAttribute("hidden");}
    const count = parent.getElementById("blockedImgCount");
    if (count) {count.textContent = String(blocked.length);}
  }
  const bugLine = parent.getElementById("webBugs");
  if (bugLine && bugs.length > 0) {
    bar.removeAttribute("hidden");
    bugLine.removeAttribute("hidden");
    const bugCount = parent.getElementById("webBugCount");
    if (bugCount) {bugCount.textContent = String(bugs.length);}
    const info = parent.querySelector("#blockedImages .info");
    if (info) {info.classList.add("hasWebBugs");}
    // the break separates the two tallies; with only one of them it is a stray line
    const breakPoint = parent.querySelector("#webBugs br");
    if (breakPoint) {
      if (blocked.length === 0) {breakPoint.remove();}
      else {breakPoint.outerHTML = "&nbsp; &bullet; &nbsp;";}
    }
  }
}

/**
 * The height the frame needs to show all of the message.
 *
 * A message that carries the end-of-page marker is measured to that marker, which
 * is what the old in-frame resizer did; otherwise the taller of the two body boxes
 * is used. Zero means the document could not be measured at all.
 *
 * @param {Document} doc the frame's document
 * @returns {number} the height in CSS pixels
 */
export function frameHeight(doc) {
  if (!doc || !doc.body) {return 0;}
  const marker = doc.querySelector(HEIGHT_MARKER);
  if (marker && typeof marker.getBoundingClientRect === "function") {
    const rect = marker.getBoundingClientRect();
    if (rect && rect.bottom > 0) {return Math.ceil(rect.bottom);}
  }
  const html = doc.documentElement;
  // a document with no layout reports no height at all, which is not a height of zero
  const bodyHeight = Number(doc.body.scrollHeight) || 0;
  const htmlHeight = html ? Number(html.scrollHeight) || 0 : 0;
  return Math.max(bodyHeight, htmlHeight);
}

/**
 * Sizes the frame to its content.
 *
 * @param {Element} frame the message iframe
 * @returns {void}
 */
export function sizeFrame(frame) {
  const doc = frameDocument(frame);
  const height = frameHeight(doc);
  if (height > 0) {frame.style.height = height + "px";}
}

/**
 * Adds the "Toggle Images" control to the info bar, and keeps it in step with what
 * the frame is showing.
 *
 * @param {Document} parent the page's document
 * @param {Element} frame the message iframe
 * @returns {void}
 */
export function createToggle(parent, frame) {
  const doc = frameDocument(frame);
  if (!doc) {return;}
  const existing = parent.getElementById("toggleBlockedImages");
  if (existing) {existing.remove();}
  const images = blockedImages(doc).concat(webBugs(doc));
  if (images.length === 0) {return;}
  const button = parent.createElement("button");
  button.id = "toggleBlockedImages";
  button.textContent = "Toggle Images";
  button.className = "on";
  button.addEventListener("click", function(event) {
    event.preventDefault();
    button.classList.toggle("on");
    setHidden(images, button.classList.contains("on"));
  });
  const info = parent.querySelector("#blockedImages .info");
  if (info) {info.appendChild(button);}
}

/**
 * Does all of it for one frame: styles, placeholders, tallies, control, size.
 *
 * @param {Document} parent the page's document
 * @param {Element} frame the message iframe
 * @returns {void}
 */
export function decorateFrame(parent, frame) {
  const doc = frameDocument(frame);
  if (!doc) {return;}
  normalizeStyles(doc);
  markBlockedImages(doc);
  const blocked = blockedImages(doc);
  const bugs = webBugs(doc);
  const showBlocked = frame.classList.contains("showBlockedImages");
  if (!showBlocked) {setHidden(blocked.concat(bugs), true);}
  showCounts(parent, blocked, bugs);
  if (!showBlocked) {createToggle(parent, frame);}
  sizeFrame(frame);
}

/**
 * Finds the message frame on the page and wires it up once its content is there.
 *
 * @returns {void}
 */
export function init() {
  const frame = document.getElementById(FRAME_ID);
  if (!frame) {return;}
  // the frame's content comes from a separate response, so its document is empty here
  frame.addEventListener("load", function() {decorateFrame(document, frame);});
  // if it finished before this page got here, the injected end-of-message marker shows it
  const doc = frameDocument(frame);
  if (doc && doc.querySelector(HEIGHT_MARKER)) {decorateFrame(document, frame);}
  window.addEventListener("resize", function() {sizeFrame(frame);});
}

if (typeof document !== "undefined" && typeof window !== "undefined") {
  document.addEventListener("DOMContentLoaded", init);
}
