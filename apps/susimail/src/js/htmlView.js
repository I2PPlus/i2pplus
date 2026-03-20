/**
 * @module htmlView
 * @file SusiMail HTML view handler.
 * Manages toggle-view buttons for switching between text and HTML mail
 * views, and applies fallback CSS styling inside the HTML iframe.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Sets up the view-toggle and "open in new tab" buttons by copying
 * the hidden toggle URL into visible control elements.
 * Handles both parent-window and iframe contexts.
 * @function initViewToggle
 * @returns {void}
 */
function initViewToggle() {
  const iframe = window.parent.document.getElementById("iframeSusiHtmlView") || document.getElementById("iframeSusiHtmlView");
  const toggleHtmlLink = window.parent.document.getElementById("toggleHtmlLink") || document.getElementById("toggleHtmlLink");
  const toggleViewMode = window.parent.document.getElementById("toggleViewMode") || document.getElementById("toggleViewMode");
  const toggleHtmlView = window.parent.document.getElementById("toggleHtmlView") || document.getElementById("toggleHtmlView");
  if (toggleHtmlLink) {
    const toggleHtmlUrl = toggleHtmlLink.href;
    const switchViewMode = window.parent.document.getElementById("switchViewMode") || document.getElementById("switchViewMode");
    switchViewMode.href = toggleHtmlUrl;
    switchViewMode.innerHTML = toggleHtmlLink.innerHTML;
    switchViewMode.classList.add("fakebutton", "script");
    toggleHtmlView.setAttribute("hidden", "hidden");
    toggleViewMode.removeAttribute("hidden");
  } else if (toggleViewMode) {
    toggleViewMode.setAttribute("hidden", "hidden");
  }
  if (iframe) {
    const newTabHtmlView = window.parent.document.getElementById("newTabHtmlView") || document.getElementById("newTabHtmlView");
    newTabHtmlView.href = iframe.src;
    newTabHtmlView.classList.add("fakebutton", "script");
    newTabHtmlView.setAttribute("target", "_blank");
    newTabHtmlView.removeAttribute("hidden");
    toggleViewMode.removeAttribute("hidden");
  }
}

/**
 * Injects a fallback CSS stylesheet into the HTML mail iframe to ensure
 * consistent font and background rendering.
 * @function setupFallbackCss
 * @returns {void}
 */
function setupFallbackCss() {
  const iframe = window.parent.document.getElementById("iframeSusiHtmlView") || document.getElementById("iframeSusiHtmlView");
  if (!iframe) {return;}
  const head = iframe.contentWindow.document.head || document.head;
  const body = iframe.contentWindow.document.body || document.body;
  const fallbackStyles = document.createElement("style");
  fallbackStyles.innerHTML = "html,body{font-family:Open Sans,Segoe UI,Noto Sans,sans-serif;color:#222;background:#fff}#endOfPage{display:block;height:15px}";
  if (head) {head.prepend(fallbackStyles);}
  else {body.prepend(fallbackStyles);}
}

document.addEventListener("DOMContentLoaded", function(event) {
  initViewToggle();
  setupFallbackCss();
});