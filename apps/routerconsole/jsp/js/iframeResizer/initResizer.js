/**
 * @module initResizer
 * @file initResizer.js - Host-page setup for the console's embedded webapp frames.
 * @description Adopts one iframe with the console's sizing settings, scrolls the
 * host and the framed page to the top whenever the framed page loads a new
 * document, and answers the framed page's own scroll and resize requests.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function initResizerSetup() {

  /**
   * Frame ids whose message listener is already wired. A framed page fires the
   * host's onInit on every navigation inside itself, so without this the host
   * would add another window listener per navigation and keep the old ones.
   *
   * @type {Set<string>}
   */
  const wiredFrames = new Set();

  /**
   * Scrolls the host page to an element of the framed page. The framed page is
   * sized to its content and has no scrollbar of its own, so the element's
   * viewport position plus the host's scroll offset is where the host must go.
   *
   * @param {Element} element the element in the framed page to scroll to
   * @returns {void}
   */
  function scrollToElement(element) {
    const elementPosition = element.getBoundingClientRect().top;
    window.scrollTo({top: elementPosition + window.scrollY, behavior: "smooth"});
  }

  /**
   * Adopts one embedded frame and wires the host-side handling of the messages
   * the framed page sends when it changes shape or asks to be scrolled.
   *
   * @param {string} frameId the id of the iframe element to adopt
   * @returns {void}
   */
  function initResizer(frameId) {
    const iframe = document.getElementById(frameId);

    if (!iframe) {return;}

    iFrameResize({
      // The frame reports through a MutationObserver, so no polling timer runs.
      heightCalculationMethod: "taggedElement",
      inPageLinks: true,
      // A frame measured while hidden reports zero; the floor keeps the host from
      // writing a 0px height, which would otherwise latch a page-wide observer
      // watching for the frame to become visible.
      minHeight: 1,
      // Ignore height changes of a couple of pixels: they come from scrollbar and
      // font-swap jitter, and each one is a round trip plus a style write.
      tolerance: 2,
      onInit: () => {
        requestAnimationFrame(() => {
          iframe.contentWindow.scrollTo(0, 0);
          window.parent.scrollTo(0, 0);
        });
      }
    }, iframe);

    if (wiredFrames.has(frameId)) {return;}
    wiredFrames.add(frameId);

    window.addEventListener("message", event => {
      if (event.origin !== window.location.origin) {return;}
      if (typeof event.data !== "object" || event.data === null) {return;}

      // The Tunnel Manager asks for a re-measure by naming the frame it lives in.
      const askedForResize = event.data.action === "resize" && event.data.iframeId === frameId;
      if (!askedForResize && event.data.origin !== "parent") {return;}

      // looked up on every message rather than captured here: a page can replace
      // the frame element and keep its id, and a listener holding the old element
      // would then size and scroll a document that is no longer on the page
      const frame = document.getElementById(frameId);
      if (frame === null) {return;}

      if (askedForResize) {
        frame.iFrameResizer?.resize();
        return;
      }

      switch (event.data.command) {
        case "scrollToElement": {
          const element = frame.contentWindow?.document.getElementById(event.data.id);
          if (element) {scrollToElement(element);}
          break;
        }

        case "resize":
          frame.iFrameResizer?.resize();
          break;
      }
    }, false);
  }

  // these stay on the window: other scripts and the page's inline
  // handlers reach them there
  window.scrollToElement = scrollToElement;
  window.initResizer = initResizer;
}());
