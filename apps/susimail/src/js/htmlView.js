/* SusiMail htmlView.js by dr|z3d */
/* Handle toggle view buttons and css fallback styles */
/* License: AGPL3 or later */

function initViewToggle() {
  const toggleHtmlLink = window.parent.document.getElementById("toggleHtmlLink") || document.getElementById("toggleHtmlLink");
  const toggleViewMode = window.parent.document.getElementById("toggleViewMode") || document.getElementById("toggleViewMode");
  const toggleHtmlView = window.parent.document.getElementById("toggleHtmlView") || document.getElementById("toggleHtmlView");
  if (toggleHtmlLink) {
    const toggleHtmlUrl = toggleHtmlLink.href;
    const fakeHtmlToggleButton = window.parent.document.getElementById("fakeHTMLToggleButton") || document.getElementById("fakeHTMLToggleButton");
    fakeHtmlToggleButton.href = toggleHtmlUrl;
    fakeHtmlToggleButton.innerHTML = toggleHtmlLink.innerHTML;
    fakeHtmlToggleButton.classList.add("fakebutton", "script");
    toggleHtmlView.setAttribute("hidden", "hidden");
    toggleViewMode.removeAttribute("hidden", "hidden");
  } else if (toggleViewMode) {
    toggleViewMode.setAttribute("hidden", "hidden");
  }
}

function setupFallbackCss() {
  const iframe = window.parent.document.getElementById("iframeSusiHtmlView") || document.getElementById("iframeSusiHtmlView");
  if (!iframe) {return;}
  const head = iframe.contentWindow.document.head || document.head;
  const body = iframe.contentWindow.document.body || document.body;
  const fallbackStyles = document.createElement("style");
  fallbackStyles.innerHTML = "html,body{font-family:Open Sans,Segoe UI,Noto Sans,sans-serif;color:#222;background:#fff}body{padding:15px!important}#endOfPage{display:block;height:15px}";
  if (head) {head.prepend(fallbackStyles);}
  else {body.prepend(fallbackStyles);}
}

document.addEventListener("DOMContentLoaded", function(event) {
  initViewToggle();
  setupFallbackCss();
});