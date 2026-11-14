/**
 * @file iframeResizer.contentWindow.js
 * @description Framed-page half of the iframe sizing pair. Measures the page
 * and reports its height to the host page. Needs iframeResizer.js in the host.
 *
 * Fork of iframe-resizer v4 (https://github.com/davidjbradshaw/iframe-resizer/tree/v4),
 * cut back to what the console actually does: height only (no width sizing), no
 * interval polling, no reset handshake, no pageInfo, no public methods the
 * webapps never call, no V1 body margin/background/padding injection, no
 * nested-host relaying, and no mouse, touch or print listeners re-measuring the
 * page on every interaction.
 *
 * The init message carries PROTOCOL so a half left in cache across an upgrade
 * warns instead of measuring garbage. Both halves ship from the same directory
 * in the router console's root context, so they always upgrade together.
 *
 * @author David J. Bradshaw - dave@bradshaw.net (original)
 * @author dr|z3d (I2P+ fork)
 * @license MIT (upstream iframe-resizer v4), AGPL3 or later (I2P+ fork)
 */

(() => {
  'use strict';

  /** Marks our messages on the wire in both directions. */
  const MSG_ID = '[iFrameSizer]';
  const MSG_ID_LEN = MSG_ID.length;

  /** Must match the host half; a mismatch means one side is a cached copy. */
  const PROTOCOL = 'i2p1';

  /** Posted to the host when this half loads after the host has looked for it. */
  const CHILD_READY = '[iFrameResizerChild]Ready';

  /** Grace period before the initial location-hash check. */
  const HASH_CHECK_MS = 128;

  /** Minimum spacing between reported sizes; raised if measuring proves slow. */
  let throttledMs = 16;

  let autoResize = true;
  let heightCalcMode = 'taggedElement';
  let firstRun = true;
  let inPageLinksEnabled = false;
  let initMsg = '';
  let logging = false;
  let myId = '';
  let tolerance = 0;

  /** Origin to post back to, taken from the host's init message. */
  let targetOrigin = '*';

  /** The window the host half runs in; taken from the init message. */
  let target = window.parent;

  /** Last size reported to the host, so an unchanged page stays quiet. */
  let height = 0;

  /** Listeners that re-measure the page, kept so autoResize(false) can remove them. */
  const triggerListeners = {};

  /**
   * Capture-phase handler that re-measures once a resource has finished loading.
   * The description is only built when logging is on: this fires for every image,
   * script and stylesheet on the page, and the throttle drops most of them.
   */
  const onResourceLoaded = event =>
    sendSize('resourceLoad', logging ? `Resource loaded: ${event.target.tagName}` : '');

  /** Observer watching the body for anything that changes its height. */
  let bodyObserver = null;

  /**
   * @param {string} msg the line to print
   * @returns {void}
   */
  function log(msg) {
    if (logging) {console.log(`${MSG_ID}[${myId}] ${msg}`);}
  }

  /**
   * Warnings print whether or not logging is on, so a broken handshake is
   * never silent.
   *
   * @param {string} msg the line to print
   * @returns {void}
   */
  function warn(msg) {
    console.warn(`${MSG_ID}[${myId}] ${msg}`);
  }

  /**
   * Underscore-style throttle: at most one call per throttledMs, and the
   * trailing call always runs with its own arguments, so the last change wins.
   *
   * @param {Function} fn the function to pace
   * @returns {Function} the paced function
   */
  function throttle(fn) {
    let timer = null;
    let previous = 0;
    let lastArgs = null;

    function later() {
      previous = Date.now();
      timer = null;
      fn(...lastArgs);
    }

    function throttled(...args) {
      const now = Date.now();
      const remaining = throttledMs - (now - previous);

      lastArgs = args;

      if (remaining <= 0 || remaining > throttledMs) {
        if (timer) {clearTimeout(timer);}
        previous = now;
        fn(...lastArgs);
      } else if (!timer) {
        timer = setTimeout(later, remaining);
      }
    }

    /** Drops a measurement that has been scheduled but not yet run. */
    throttled.cancel = () => {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    };

    return throttled;
  }

  /**
   * Reads one CSS length as pixels. Margins are added by hand because the body
   * box height does not include them.
   *
   * @param {string} prop the CSS property to read, e.g. marginTop
   * @param {Element} [el] the element to read it from, the body by default
   * @returns {number} the length in pixels, 0 when it cannot be read
   */
  function getCssPixels(prop, el) {
    const computed = window.getComputedStyle(el || document.body);
    return parseInt(computed[prop], 10) || 0;
  }

  /**
   * Raises the throttle when measuring the page turns out to be slow, so a
   * large page cannot spend its time re-measuring itself.
   *
   * @param {number} elapsed how long the last measurement took, in ms
   * @returns {void}
   */
  function chkEventThrottle(elapsed) {
    if (elapsed > throttledMs / 2) {
      throttledMs = 2 * elapsed;
      log(`Event throttle increased to ${throttledMs}ms`);
    }
  }

  /**
   * Parses the settings out of the host's init message.
   *
   * @returns {void}
   */
  function readDataFromParent() {
    const data = initMsg.slice(MSG_ID_LEN).split(':');

    myId = data[0];
    logging = data[2] === 'true';
    autoResize = data[3] === 'true';
    tolerance = Number(data[4]) || 0;
    inPageLinksEnabled = data[5] === 'true';
    heightCalcMode = data[6] || heightCalcMode;
  }

  /**
   * Adds the end-of-page marker the height is measured from. Framed pages emit
   * their own `<span data-iframe-height>`; this covers the ones that do not, so
   * there is always a bottom edge to report.
   *
   * @returns {void}
   */
  function ensureHeightMarker() {
    if (document.querySelector('[data-iframe-height]')) {return;}

    const marker = document.createElement('span');
    marker.id = 'endOfPage';
    marker.setAttribute('data-iframe-height', '');
    document.body.appendChild(marker);
    log('Added end-of-page marker');
  }

  /**
   * Appends a float-clearing element so the reported height includes floated
   * content; a page that ends inside a float otherwise reports short.
   *
   * @returns {void}
   */
  function injectClearFixIntoBodyElement() {
    const clearFix = document.createElement('div');
    // display and height are set explicitly because a page's own CSS may have
    // redefined those keywords globally.
    clearFix.style.clear = 'both';
    clearFix.style.display = 'block';
    clearFix.style.height = '0';
    document.body.appendChild(clearFix);
  }

  /**
   * Clears any inline height on the root elements. A height set here would be
   * fed back into the next measurement, and the frame would grow forever.
   *
   * @returns {void}
   */
  function stopInfiniteResizingOfIFrame() {
    document.documentElement.style.height = '';
    document.body.style.height = '';
  }

  /**
   * Largest edge position among a set of elements.
   *
   * @param {string} side the bounding-rect side to maximise, bottom or right
   * @param {Element[]} elements the elements to measure
   * @returns {number} the largest edge position in pixels
   */
  function getMaxElement(side, elements) {
    const sideName = side.charAt(0).toUpperCase() + side.slice(1);
    const started = Date.now();
    let maxVal = 0;

    for (const element of elements) {
      const elVal = element.getBoundingClientRect()[side] + getCssPixels(`margin${sideName}`, element);
      if (elVal > maxVal) {maxVal = elVal;}
    }

    const elapsed = Date.now() - started;
    log(`Parsed ${elements.length} HTML elements -> position calculated in ${elapsed}ms`);
    chkEventThrottle(elapsed);

    return maxVal;
  }

  const getHeight = {
    /**
     * Bottom edge of the lowest tagged element, the console's chosen method.
     * Falls back to the body box when a page has no marker at all, rather than
     * measuring every element on the page as upstream does.
     *
     * @returns {number} the page height in pixels
     */
    taggedElement() {
      const tagged = document.querySelectorAll('[data-iframe-height]');

      if (tagged.length === 0) {
        warn('No tagged elements (data-iframe-height) found on page -> using body offset');
        return getHeight.bodyOffset();
      }
      return getMaxElement('bottom', tagged);
    },

    /**
     * Body box height plus its margins.
     *
     * @returns {number} the page height in pixels
     */
    bodyOffset() {
      return document.body.offsetHeight + getCssPixels('marginTop') + getCssPixels('marginBottom');
    }
  };

  /**
   * Rejects a calculation method this build does not implement.
   *
   * @returns {void}
   */
  function checkHeightMode() {
    if (!(heightCalcMode in getHeight)) {
      warn(`${heightCalcMode} is not a valid option for heightCalculationMethod -> using taggedElement`);
      heightCalcMode = 'taggedElement';
    }
  }

  /**
   * Adds or removes one page-change listener that asks for a new size.
   *
   * @param {string} method 'add' or 'remove'
   * @param {string} eventName the event to listen for
   * @param {string} eventType what the listener is for, in the log
   * @returns {void}
   */
  function manageTriggerEvent(method, eventName, eventType) {
    if (method === 'add') {
      triggerListeners[eventName] = () => sendSize(eventName, eventType);
      window.addEventListener(eventName, triggerListeners[eventName], {passive: true});
    } else {
      const handleEvent = triggerListeners[eventName];
      delete triggerListeners[eventName];
      if (handleEvent) {window.removeEventListener(eventName, handleEvent);}
    }
  }

  /**
   * Adds or removes every listener that re-measures the page.
   *
   * Deliberately left out: mouse, touch and print events, which re-measure on
   * interaction rather than on content change; `transitioniteration`, which is
   * not a real event name; the animation and transition *start* events, which
   * report geometry mid-flight that the matching end event then corrects; and
   * `orientationchange`, which the host already answers by asking every frame it
   * holds to re-measure.
   *
   * @param {string} method 'add' or 'remove'
   * @returns {void}
   */
  function manageEventListeners(method) {
    const events = [
      ['animationend', 'Animation End'],
      ['transitionend', 'Transition End'],
      ['input', 'Input'],
      ['readystatechange', 'Ready State Change']
    ];

    for (const [eventName, eventType] of events) {manageTriggerEvent(method, eventName, eventType);}
  }

  /**
   * Re-measures when a resource finishes loading. Images are the common case:
   * the tag is in the DOM long before the pixels are, so the height changes
   * after the mutation observer has already fired. Capture phase is used
   * because load and error do not bubble, which also avoids upstream's
   * per-element listeners that kept detached images alive. One stable handler
   * is shared, so arming it twice is a no-op and it can always be removed.
   *
   * @param {string} method 'add' or 'remove'
   * @returns {void}
   */
  function manageResourceListeners(method) {
    if (method === 'add') {
      document.addEventListener('load', onResourceLoaded, true);
      document.addEventListener('error', onResourceLoaded, true);
    } else {
      document.removeEventListener('load', onResourceLoaded, true);
      document.removeEventListener('error', onResourceLoaded, true);
    }
  }

  /**
   * Watches the body for anything that changes its height. This is the primary
   * reporting path: the console pages set no polling interval.
   *
   * @returns {void}
   */
  function setupMutationObserver() {
    bodyObserver = new MutationObserver(mutations => {
      // the description is built only when it will be printed: this is the
      // hottest path on the page, and the throttle drops most of what it sees
      sendSize('mutationObserver',
        logging ? `Mutation observed: ${mutations[0].target.tagName} ${mutations[0].type}` : '');
    });
    bodyObserver.observe(document.body, {
      attributes: true,
      attributeOldValue: false,
      characterData: true,
      characterDataOldValue: false,
      childList: true,
      subtree: true
    });
  }

  /** Stops every listener that re-measures the page. */
  function stopEventListeners() {
    manageEventListeners('remove');
    manageResourceListeners('remove');
    // A measurement already scheduled would still land and resize the frame
    // after the page asked to be left alone.
    sizeIFrameThrottled.cancel();
    if (bodyObserver !== null) {
      bodyObserver.disconnect();
      bodyObserver = null;
    }
  }

  /** Arms the listeners that re-measure the page, if auto-resize is on. */
  function startEventListeners() {
    if (autoResize === true) {
      manageEventListeners('add');
      manageResourceListeners('add');
      setupMutationObserver();
    } else {
      log('Auto Resize disabled');
    }
  }

  /**
   * Turns in-page links into parent scrolls: an anchor this page holds is
   * reported as an offset for the host to scroll to, one it does not hold is
   * handed back to the host to look for.
   *
   * @param {string} location the link, with or without a leading `#`
   * @returns {void}
   */
  function findTargetInPage(location) {
    const hash = location.split('#')[1] || location;
    const hashData = decodeURIComponent(hash);
    const element = document.getElementById(hashData) || document.getElementsByName(hashData)[0];

    if (!element) {
      log(`In page link #${hash} not found in frame -> sending to host`);
      sendMsg(0, 0, 'inPageLink', `#${hash}`);
      return;
    }

    const rect = element.getBoundingClientRect();
    const jumpPosition = {x: rect.left + window.scrollX, y: rect.top + window.scrollY};

    log(`Moving to in page link #${hash} -> x: ${jumpPosition.x} * y: ${jumpPosition.y}`);
    // The height and width fields carry y and x in a scrollToOffset message.
    sendMsg(jumpPosition.y, jumpPosition.x, 'scrollToOffset');
  }

  /**
   * Binds every `#anchor` link on the page to the in-page link handler, and
   * watches the location hash for links this page was loaded with.
   *
   * @returns {void}
   */
  function setupInPageLinks() {
    function checkLocationHash() {
      const hash = window.location.hash;
      if (hash !== '' && hash !== '#') {findTargetInPage(window.location.href);}
    }

    if (!inPageLinksEnabled) {
      log('In page linking not enabled');
      return;
    }

    log('Setting up location.hash handlers');
    for (const el of document.querySelectorAll('a[href^="#"]')) {
      const href = el.getAttribute('href');
      if (href !== '#') {
        el.addEventListener('click', event => {
          event.preventDefault();
          findTargetInPage(href);
        });
      }
    }
    window.addEventListener('hashchange', checkLocationHash);
    setTimeout(checkLocationHash, HASH_CHECK_MS);
  }

  /**
   * Installs the methods a framed page can call on itself to drive the host
   * frame. The Tunnel Manager's down-state pause is the current user of
   * autoResize(); moveToAnchor() and size() are the library's public API, kept
   * so a framed page can scroll or size itself without inventing its own
   * postMessage protocol for the purpose.
   *
   * @returns {void}
   */
  function setupPublicMethods() {
    window.parentIFrame = {
      /**
       * Pauses or resumes auto-resizing. Pausing also tells the host to stop
       * asking this frame to re-measure on its own window events.
       *
       * @param {boolean} resize true to resume, false to pause
       * @returns {boolean} the state the frame is now in
       */
      autoResize(resize) {
        if (resize === true && autoResize === false) {
          autoResize = true;
          startEventListeners();
        } else if (resize === false && autoResize === true) {
          autoResize = false;
          stopEventListeners();
        }
        sendMsg(0, 0, 'autoResize', String(autoResize));
        return autoResize;
      },

      /**
       * Scrolls the host page to an element this page holds.
       *
       * @param {string} hash the element id, with or without a leading `#`
       * @returns {void}
       */
      moveToAnchor(hash) {
        findTargetInPage(hash);
      },

      /**
       * Reports a height this page chose, instead of a measured one.
       *
       * @param {number} [customHeight] the height to report, in pixels
       * @returns {void}
       */
      size(customHeight) {
        sendSize('size', `parentIFrame.size(${customHeight || ''})`, customHeight);
      }
    };
  }

  /**
   * True when a change is big enough to be worth reporting.
   *
   * @param {number} next the height measured now
   * @returns {boolean} true when the host should be told
   */
  function isSizeChangeDetected(next) {
    return Math.abs(height - next) > tolerance;
  }

  /**
   * Measures the page and reports it when the measurement changed. Width is
   * always 0: the console sizes frames by height only, and measuring width
   * would cost two more layout reads on every pass.
   *
   * @param {string} triggerEvent the message type to send
   * @param {string} triggerEventDesc what caused it, for the log
   * @param {number} [customHeight] a height the page chose, to skip measuring
   * @returns {void}
   */
  function sizeIFrame(triggerEvent, triggerEventDesc, customHeight) {
    const currentHeight = customHeight === undefined ? getHeight[heightCalcMode]() : customHeight;

    if (isSizeChangeDetected(currentHeight) || triggerEvent === 'init') {
      height = currentHeight;
      sendMsg(height, 0, triggerEvent);
    } else {
      log('No change in size detected');
    }
  }

  const sizeIFrameThrottled = throttle(sizeIFrame);

  /**
   * Entry point for every "something may have changed" signal. The init
   * message bypasses the throttle so the frame gets its first height at once.
   *
   * @param {string} triggerEvent the message type to send
   * @param {string} triggerEventDesc what caused it, for the log
   * @param {number} [customHeight] a height the page chose
   * @returns {void}
   */
  function sendSize(triggerEvent, triggerEventDesc, customHeight) {
    if (triggerEvent !== 'init' && triggerEventDesc !== '') {log(`Trigger event: ${triggerEventDesc}`);}

    if (triggerEvent === 'init') {sizeIFrame(triggerEvent, triggerEventDesc, customHeight);}
    else {sizeIFrameThrottled(triggerEvent, triggerEventDesc, customHeight);}
  }

  /**
   * Posts one message to the host page.
   *
   * @param {number} h the height, or the y of a scroll request
   * @param {number} w the width, or the x of a scroll request
   * @param {string} triggerEvent the message type
   * @param {string} [msg] extra payload for the host
   * @returns {void}
   */
  function sendMsg(h, w, triggerEvent, msg) {
    const extra = msg === undefined ? '' : `:${msg}`;
    const message = `${myId}:${h}:${w}:${triggerEvent}${extra}`;

    log(`Sending message to host page (${message})`);
    target.postMessage(MSG_ID + message, targetOrigin);
  }

  /** Runs the page-side setup once the host has said hello. */
  function init() {
    readDataFromParent();
    log(`Initialising iFrame (${window.location.href})`);
    checkHeightMode();
    injectClearFixIntoBodyElement();
    ensureHeightMarker();
    stopInfiniteResizingOfIFrame();
    setupPublicMethods();
    setupInPageLinks();
    startEventListeners();
    sendSize('init', 'Init message from host page');
  }

  /**
   * The frame's message listener. The host addresses messages by type, not by
   * frame, so only the init message carries the settings.
   *
   * @param {MessageEvent} event the message as delivered
   * @returns {void}
   */
  function receiver(event) {
    const data = String(event.data);

    if (MSG_ID !== data.slice(0, MSG_ID_LEN)) {return;}

    const body = data.slice(MSG_ID_LEN);
    const messageType = body.split(':')[0];
    const isInitMsg = body.split(':')[1] === PROTOCOL;

    function getPayload() {
      return data.slice(data.indexOf(':') + 1);
    }

    if (firstRun) {
      if (!isInitMsg) {
        log(`Ignored message of type "${messageType}" -> received before initialization`);
        return;
      }

      initMsg = data;
      target = event.source;
      // Same-origin frames only: from here on the host's own origin is the
      // only one worth posting back to.
      targetOrigin = event.origin && event.origin !== 'null' ? event.origin : '*';
      init();
      firstRun = false;
      return;
    }

    switch (messageType) {
      case 'resize':
        sendSize('resizeParent', 'Parent window requested size check');
        break;

      case 'moveToAnchor':
        findTargetInPage(getPayload());
        break;

      default:
        // A repeated init is normal and quiet: the host re-triggers it when
        // this half loaded after it looked for the frame, and the page is
        // already measuring by then.
        if (!isInitMsg) {warn(`Unexpected message (${data})`);}
    }
  }

  /**
   * Tells the host to start the handshake when this half loaded after the host
   * had already looked for a frame to talk to.
   *
   * @returns {void}
   */
  function chkLateLoaded() {
    if (document.readyState === 'loading') {return;}

    let parentOrigin = '*';
    try {
      parentOrigin = window.parent.location.origin;
    } catch (error) {
      // A cross-origin parent will not give up its origin, so post anywhere.
    }
    window.parent.postMessage(CHILD_READY, parentOrigin);
  }

  window.addEventListener('message', receiver, false);
  window.addEventListener('readystatechange', chkLateLoaded, false);
  chkLateLoaded();
})();
