/**
 * @module setupIframe
 * @description Marks a page that is running inside an iframe with the "iframed"
 * class, which the webapps' own scripts test to decide whether to talk to their
 * host page. The end-of-page marker used for height reporting is added by
 * iframeResizer.contentWindow.js, which every framed page loads alongside this.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function setupIframeSetup() {

  /**
   * Adds the "iframed" class to the HTML element when this page is framed.
   *
   * @function setupIframe
   * @returns {void}
   */
  function setupIframe() {
    if (window.top !== window.self) {document.documentElement.classList.add("iframed");}
  }

  // Deferred one task so the class lands in the same place in the page's task
  // order the webapp scripts already assume.
  setTimeout(setupIframe, 0);

  // these stay on the window: other scripts and the page's inline
  // handlers reach them there
  window.setupIframe = setupIframe;
}());
