/**
 * @file iframeResizer.js
 * @description Host-page half of the iframe sizing pair. Adopts an &lt;iframe&gt;,
 * posts the framed page the settings it must measure with, and writes back the
 * height that page reports. Needs iframeResizer.contentWindow.js in the framed
 * page.
 *
 * This is a fork of iframe-resizer v4 (https://github.com/davidjbradshaw/iframe-resizer/tree/v4),
 * cut back to what the console actually does: one level of nesting, same-origin
 * frames, height only, a tagged end-of-page element as the height source, and
 * MutationObserver reporting inside the frame. Gone are width sizing, interval
 * polling, the reset handshake, pageInfo, the close/onClose hooks, the mouse
 * hooks, the V1 body-margin path, the nested-host/middle-tier relaying, the
 * AMD/CommonJS exports and the deprecated-option renames.
 *
 * The init message carries PROTOCOL so a half left in cache across an upgrade
 * warns instead of measuring garbage. Both halves ship from the same directory
 * in the router console's root context, so they always upgrade together.
 *
 * Upstream contributors carried with this file: Jure Mav, Reed Dadoune.
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

  /** Framed pages that do not answer with this tag are running the other half. */
  const PROTOCOL = 'i2p1';

  /** Posted by a late-loaded frame half to ask the host to start again. */
  const CHILD_READY = '[iFrameResizerChild]Ready';

  /** Coalesces window-level triggers so one resize pass serves every frame. */
  const DEBOUNCE_MS = 16;

  const DEFAULTS = {
    autoResize: true,
    checkOrigin: true,
    heightCalculationMethod: 'taggedElement',
    id: 'iFrameResizer',
    inPageLinks: false,
    log: false,
    maxHeight: Infinity,
    minHeight: 0,
    onInit: () => {},
    onResized: () => {},
    onScroll: () => true,
    tolerance: 0
  };

  /** Per-frame state, keyed by iframe id. A Map keeps ids like `toString` safe. */
  const settings = new Map();

  /** Scroll position to restore after sizing, or null when nothing is pending. */
  let pagePosition = null;

  /** Shared debounce timer for window resize / visibility triggers. */
  let debounceTimer = null;

  /** Observer that recovers frames sized to zero while they were hidden. */
  let hiddenObserver = null;

  /** Suffix source for iframes that arrive without an id. */
  let count = 0;

  /**
   * Whether this frame's traffic should reach the console.
   *
   * @param {string} iframeId the frame the message names
   * @returns {boolean} true when the frame was adopted with log enabled
   */
  function isLogEnabled(iframeId) {
    const entry = settings.get(iframeId);
    return Boolean(entry && entry.log);
  }

  /**
   * @param {string} iframeId the frame the line is about
   * @returns {string} the console prefix naming that frame
   */
  function logHeader(iframeId) {
    return `${MSG_ID}[${iframeId}]`;
  }

  /**
   * @param {string} iframeId the frame the line is about
   * @param {string} msg the line to print
   * @returns {void}
   */
  function log(iframeId, msg) {
    if (isLogEnabled(iframeId)) {console.log(logHeader(iframeId), msg);}
  }

  /**
   * Warnings print whether or not logging is on, so a broken handshake is
   * never silent.
   *
   * @param {string} iframeId the frame the line is about
   * @param {string} msg the line to print
   * @returns {void}
   */
  function warn(iframeId, msg) {
    console.warn(logHeader(iframeId), msg);
  }

  /**
   * Runs fn on the next animation frame, unless the caller says this message
   * type must be handled synchronously (init, so the first size lands before
   * anything else can observe the frame at its default height).
   *
   * @param {Function} fn the sizing pass
   * @param {string} type the message type that triggered it
   * @param {string} syncTypes a type that must not wait for a frame
   * @returns {void}
   */
  function syncResize(fn, type, syncTypes) {
    if (type !== syncTypes) {window.requestAnimationFrame(fn);}
    else {fn();}
  }

  /**
   * Coalesces repeated calls into one pass after time ms.
   *
   * @param {Function} fn the pass to run
   * @param {number} time the quiet period in ms
   * @returns {void}
   */
  function debounce(fn, time) {
    if (debounceTimer === null) {
      debounceTimer = setTimeout(() => {
        debounceTimer = null;
        fn();
      }, time);
    }
  }

  /**
   * Splits a frame message into its parts. The payload keeps its colons, so it
   * is sliced out after the four fixed fields rather than taken from the split.
   *
   * @param {string} msg the wire message, MSG_ID prefix included
   * @returns {{id: string, height: number, width: number, type: string,
   *          payload: string}} the message parts
   */
  function parseMsg(msg) {
    const body = msg.slice(MSG_ID_LEN);
    const fields = body.split(':');
    const [id, height, width, type] = fields;
    return {
      id,
      height: parseInt(height, 10) || 0,
      width: parseInt(width, 10) || 0,
      type,
      payload: body.slice(fields.slice(0, 4).join(':').length + 1)
    };
  }

  /**
   * Whether msg is ours: our prefix, naming a frame this page adopted. The type
   * test comes first because the console's other scripts post objects on the
   * same window, and this runs for every one of them.
   *
   * @param {string} msg the candidate message
   * @returns {boolean} true when the rest of the handler should run
   */
  function isMessageForUs(msg) {
    if (typeof msg !== 'string') {return false;}
    if (MSG_ID !== msg.slice(0, MSG_ID_LEN)) {return false;}
    return settings.has(msg.slice(MSG_ID_LEN).split(':')[0]);
  }

  /**
   * Builds the init message: the settings the frame half measures with.
   *
   * @param {string} iframeId the frame being initialised
   * @returns {string} the message body, without the MSG_ID prefix
   */
  function createInitMsg(iframeId) {
    const entry = settings.get(iframeId);
    return [
      iframeId,
      PROTOCOL,
      entry.log,
      entry.autoResize,
      entry.tolerance,
      entry.inPageLinks,
      entry.heightCalculationMethod
    ].join(':');
  }

  /**
   * Records the scroll position we are about to disturb, so sizing a frame
   * cannot move the page out from under the reader.
   *
   * @param {string} iframeId the frame being sized
   * @returns {void}
   */
  function getPagePosition(iframeId) {
    if (pagePosition === null) {
      pagePosition = {x: window.scrollX, y: window.scrollY};
      log(iframeId, `Get page position: ${pagePosition.x}, ${pagePosition.y}`);
    }
  }

  /**
   * @param {string} iframeId the frame being sized
   * @returns {void}
   */
  function setPagePosition(iframeId) {
    if (pagePosition !== null) {
      window.scrollTo(pagePosition.x, pagePosition.y);
      log(iframeId, `Set page position: ${pagePosition.x}, ${pagePosition.y}`);
      unsetPagePosition();
    }
  }

  /** @returns {void} */
  function unsetPagePosition() {
    pagePosition = null;
  }

  /**
   * Page coordinates of an element in this document.
   *
   * @param {Element} target the element to locate
   * @param {string} iframeId the frame whose message asked for it, for the log
   * @returns {{x: number, y: number}} its position from the document origin
   */
  function getElementPosition(target, iframeId) {
    const rect = target.getBoundingClientRect();
    getPagePosition(iframeId);
    return {
      x: Math.floor(rect.left + pagePosition.x),
      y: Math.floor(rect.top + pagePosition.y)
    };
  }

  /**
   * Scrolls this page to pagePosition unless onScroll handled it and said so.
   *
   * @param {string} iframeId the frame whose message asked for the scroll
   * @returns {void}
   */
  function scrollTo(iframeId) {
    if (settings.get(iframeId).onScroll(pagePosition) === false) {unsetPagePosition();}
    else {setPagePosition(iframeId);}
  }

  /**
   * Handles a frame asking to be scrolled to an element it holds: the frame
   * knows the element's position inside itself, this page adds the frame's own
   * offset, which is the part the frame cannot see.
   *
   * @param {{id: string, width: number, height: number, iframe: Element}} data
   *   the message; width and height carry the frame-relative x and y
   * @returns {void}
   */
  function scrollRequestFromChild(data) {
    const offset = getElementPosition(data.iframe, data.id);
    const newPosition = {x: data.width + offset.x, y: data.height + offset.y};

    log(data.id, `Reposition requested from frame -> offset x: ${offset.x} * y: ${offset.y}`);

    pagePosition = newPosition;
    scrollTo(data.id);
  }

  /**
   * Handles an in-page link the frame does not hold: look for the anchor here.
   *
   * @param {string} iframeId the frame that forwarded the link
   * @param {string} location the link, `#`-prefixed
   * @returns {void}
   */
  function findTarget(iframeId, location) {
    const hash = decodeURIComponent(location.split('#')[1] || '');
    const target = document.getElementById(hash) || document.getElementsByName(hash)[0];

    if (!target) {
      log(iframeId, `In page link #${hash} not found`);
      return;
    }

    const jumpPosition = getElementPosition(target, iframeId);
    log(iframeId, `Moving to in page link #${hash} -> x: ${jumpPosition.x} * y: ${jumpPosition.y}`);
    pagePosition = jumpPosition;
    scrollTo(iframeId);
  }

  /**
   * Clamps a reported size to this frame's min/max.
   *
   * @param {string} iframeId the frame being sized
   * @param {number} size the height the frame reported
   * @returns {number} the height to apply
   */
  function clampHeight(iframeId, size) {
    const entry = settings.get(iframeId);
    let sizeInRange = size;

    if (sizeInRange < entry.minHeight) {
      sizeInRange = entry.minHeight;
      log(iframeId, `Set height to min value: ${sizeInRange}`);
    }
    if (sizeInRange > entry.maxHeight) {
      sizeInRange = entry.maxHeight;
      log(iframeId, `Set height to max value: ${sizeInRange}`);
    }
    return sizeInRange;
  }

  /**
   * Reads the padding and border the frame element adds around its content
   * box. With box-sizing: border-box the CSS height includes them, so the
   * reported content height has to be topped up or the bottom of the framed
   * page is clipped.
   *
   * The result is cached on the frame's state because it is a forced style
   * read, and a frame's own box styling does not change while it is adopted.
   * Callers that can legitimately see it change - the init handshake, a
   * navigation inside the frame, a window resize - pass refreshBox to trigger.
   *
   * @param {{iframe: Element, paddingBorder: number}} entry the frame's state
   * @returns {void}
   */
  function readBoxExtras(entry) {
    const compStyle = window.getComputedStyle(entry.iframe);

    entry.paddingBorder = compStyle.boxSizing === 'border-box'
      ? (parseInt(compStyle.paddingTop, 10) || 0) + (parseInt(compStyle.paddingBottom, 10) || 0) +
        (parseInt(compStyle.borderTopWidth, 10) || 0) + (parseInt(compStyle.borderBottomWidth, 10) || 0)
      : 0;
  }

  /**
   * Writes the height onto the frame element.
   *
   * @param {{id: string, iframe: Element, height: number}} data the size message
   * @param {number} reported the height before min/max clamping, which is what
   *   says whether the frame was hidden when it measured itself
   * @returns {void}
   */
  function setSize(data, reported) {
    data.iframe.style.height = `${data.height}px`;
    log(data.id, `IFrame [${data.id}] height set to ${data.height}px`);

    // A hidden frame measures zero. Remember it so it is re-measured once it
    // is shown, instead of staying collapsed for the rest of the session.
    if (reported === 0) {
      settings.get(data.id).zeroSized = true;
      watchHiddenFrames(data.id);
    }
  }

  /**
   * Sizes a frame to the height it reported.
   *
   * @param {{id: string, iframe: Element, height: number, type: string}} data
   *   the size message
   * @returns {void}
   */
  function resizeIFrame(data) {
    const entry = settings.get(data.id);
    const reported = data.height;

    function resize() {
      setSize(data, reported);
      setPagePosition(data.id);
      entry.onResized(data);
    }

    data.height = clampHeight(data.id, data.height + entry.paddingBorder);
    syncResize(resize, data.type, 'init');
  }

  /**
   * Re-measures every frame that measured itself while hidden, and retires the
   * observer once none is left. The console pages start their frames
   * `display:none`, so this is how a frame gets a real height at all.
   *
   * @returns {void}
   */
  function checkHiddenFrames() {
    let stillHidden = false;

    for (const [id, entry] of settings) {
      if (!entry.zeroSized) {continue;}

      if (entry.iframe.offsetParent === null) {
        stillHidden = true;
      } else {
        entry.zeroSized = false;
        trigger('Visibility change', 'resize', entry.iframe, id, true);
      }
    }

    if (!stillHidden && hiddenObserver !== null) {
      hiddenObserver.disconnect();
      hiddenObserver = null;
    }
  }

  /**
   * Arms the observer that watches for hidden frames becoming visible. Text
   * changes are deliberately not watched: they never reveal a frame, and the
   * console refreshes text constantly.
   *
   * @param {string} iframeId the frame that measured zero
   * @returns {void}
   */
  function watchHiddenFrames(iframeId) {
    if (hiddenObserver !== null) {return;}

    log(iframeId, 'Hidden iFrame detected, creating visibility listener');
    hiddenObserver = new MutationObserver(() => debounce(checkHiddenFrames, DEBOUNCE_MS));
    hiddenObserver.observe(document.body, {attributes: true, childList: true, subtree: true});
  }

  /**
   * Posts a message into a frame and asks it to act on it.
   *
   * @param {string} calleeMsg what is asking, for the log
   * @param {string} msg the message body, without the MSG_ID prefix
   * @param {Element} iframe the frame to post to
   * @param {string} [id] the frame id, defaulting to the element's own
   * @param {boolean} [refreshBox] true to re-read the frame's padding and
   *   border, which only the init handshake and a window-level resize need
   * @returns {void}
   */
  function trigger(calleeMsg, msg, iframe, id, refreshBox) {
    id = id || (iframe && iframe.id);
    const entry = settings.get(id);

    if (!entry) {return;}
    if (!iframe || !iframe.contentWindow) {
      warn(id, `${calleeMsg} -> IFrame [${id}] not found`);
      return;
    }
    if (refreshBox) {readBoxExtras(entry);}

    log(id, `${calleeMsg} -> sending to IFrame [${id}] (${msg}) targetOrigin: ${entry.targetOrigin}`);
    iframe.contentWindow.postMessage(MSG_ID + msg, entry.targetOrigin);
  }

  /**
   * Asks every frame that has finished initialising to re-measure itself.
   *
   * @param {string} eventName what caused the pass, for the log
   * @returns {void}
   */
  function sendTriggerMsg(eventName) {
    for (const [id, entry] of settings) {
      if (entry.autoResize && !entry.firstRun) {
        trigger(eventName, 'resize', entry.iframe, id, true);
      }
    }
  }

  /**
   * Asks every adopted frame to start the handshake again, for a frame half
   * that loaded after us.
   *
   * @returns {void}
   */
  function iFrameReadyMsgReceived() {
    for (const [id, entry] of settings) {
      trigger('iFrame requested init', createInitMsg(id), entry.iframe, id, true);
    }
  }

  /**
   * Removes a frame that has left the document and forgets its state.
   *
   * @param {Element} iframe the frame that was removed
   * @returns {void}
   */
  function removeIframeListeners(iframe) {
    settings.delete(iframe.id);
  }

  /**
   * Drops a frame from the page and its bookkeeping. Reached from the observer
   * that watches the frame's parent, so a frame removed by other page script
   * cannot leave a settings entry behind.
   *
   * @param {Element} iframe the frame to remove
   * @returns {void}
   */
  function closeIFrame(iframe) {
    const iframeId = iframe.id;

    log(iframeId, `Removing IFrame: ${iframeId}`);
    try {
      if (iframe.parentNode) {iframe.parentNode.removeChild(iframe);}
    } catch (error) {
      warn(iframeId, `Removing IFrame [${iframeId}] failed -> ${error.message}`);
    }
    removeIframeListeners(iframe);
  }

  /**
   * Refuses a message whose origin is not the frame's own origin. Throwing is
   * deliberate: a page that starts posting from elsewhere is a configuration
   * mistake worth seeing, and the frame keeps the size it already had.
   *
   * @param {{id: string, iframe: Element}} data the message
   * @param {{remoteHost: string, checkOrigin: (boolean|string[])}} entry the
   *   frame's state
   * @param {MessageEvent} event the event the message arrived on
   * @returns {boolean} true when the message may be acted on
   */
  function isMessageFromIFrame(data, entry, event) {
    const origin = event.origin;

    function checkAllowedOrigin() {
      const allowed = entry.checkOrigin;

      if (Array.isArray(allowed)) {
        log(data.id, `Checking connection is from allowed list of origins: ${allowed}`);
        return allowed.includes(origin);
      }
      log(data.id, `Checking connection is from: ${entry.remoteHost}`);
      return origin === entry.remoteHost;
    }

    if (entry.checkOrigin && `${origin}` !== 'null' && !checkAllowedOrigin()) {
      throw new Error(`Unexpected message received from: ${origin} for ${data.iframe.id}. ` +
        `Message was: ${event.data}. ` +
        'This error can be disabled by setting the checkOrigin: false option ' +
        'or by providing an array of trusted domains.');
    }

    return true;
  }

  /**
   * Acts on one message from a frame.
   *
   * @param {{id: string, type: string, payload: string, iframe: Element}} data
   *   the message
   * @returns {void}
   */
  function actionMsg(data) {
    const entry = settings.get(data.id);

    // Window-level triggers wait for the frame to speak once, so a resize
    // cannot race the handshake.
    entry.firstRun = false;

    switch (data.type) {
      case 'autoResize':
        entry.autoResize = data.payload === 'true';
        log(data.id, `Frame auto-resize -> ${entry.autoResize ? 'enabled' : 'disabled'}`);
        break;

      case 'scrollToOffset':
        scrollRequestFromChild(data);
        break;

      case 'inPageLink':
        findTarget(data.id, data.payload);
        break;

      case 'init':
        resizeIFrame(data);
        entry.onInit(data.iframe);
        break;

      default:
        resizeIFrame(data);
    }
  }

  /**
   * The host page's message listener.
   *
   * @param {MessageEvent} event the message as delivered
   * @returns {void}
   */
  function iFrameListener(event) {
    const msg = event.data;

    if (CHILD_READY === msg) {
      iFrameReadyMsgReceived();
      return;
    }

    if (!isMessageForUs(msg)) {return;}

    const data = parseMsg(msg);
    const entry = settings.get(data.id);

    if (!entry) {
      warn(data.id, `IFrame (${data.id}) not found`);
      return;
    }

    data.iframe = entry.iframe;

    if (!isMessageFromIFrame(data, entry, event)) {return;}

    log(data.id, `Received: ${msg}`);

    actionMsg(data);
  }

  /**
   * Gives a frame without an id one, so its messages can name it.
   *
   * @param {Element} iframe the frame to name
   * @param {Object} options the options it was adopted with
   * @returns {string} the id the frame now has
   */
  function ensureHasId(iframe, options) {
    let iframeId = iframe.id;

    if (iframeId === '') {
      iframeId = ((options && options.id) || DEFAULTS.id) + count++;
      if (document.getElementById(iframeId) !== null) {iframeId += count++;}
      iframe.id = iframeId;
    }

    return iframeId;
  }

  /**
   * Copies the caller's options over the defaults and works out the origin to
   * post to. The frame's src is read as an absolute URL, so a relative src
   * still yields a usable origin.
   *
   * @param {Element} iframe the frame being adopted
   * @param {Object} options the caller's options
   * @param {string} iframeId the id the frame is keyed by
   * @returns {void}
   */
  function processOptions(iframe, options, iframeId) {
    function checkOptions(opts) {
      if (typeof opts !== 'object') {throw new TypeError('Options is not an object');}
    }

    function copyOptions(entry, opts) {
      for (const option of Object.keys(DEFAULTS)) {
        entry[option] = Object.prototype.hasOwnProperty.call(opts, option) ? opts[option] : DEFAULTS[option];
      }
    }

    function getTargetOrigin(remoteHost) {
      return remoteHost === '' || /^(about:blank|javascript:|file:\/\/)/.test(remoteHost) ? '*' : remoteHost;
    }

    checkOptions(options || {});

    const entry = {
      firstRun: true,
      iframe,
      zeroSized: false,
      paddingBorder: 0,
      remoteHost: iframe.src && iframe.src.split('/').slice(0, 3).join('/')
    };

    copyOptions(entry, options || {});
    settings.set(iframeId, entry);

    entry.targetOrigin = entry.checkOrigin ? getTargetOrigin(entry.remoteHost) : '*';
  }

  /**
   * Applies min/max as CSS so a reported height can never be clipped by the
   * frame's own box, and rejects an inverted range.
   *
   * @param {string} iframeId the frame being adopted
   * @param {Element} iframe the frame to style
   * @returns {void}
   */
  function setLimits(iframeId, iframe) {
    function addStyle(style) {
      const styleValue = settings.get(iframeId)[style];

      if (styleValue !== Infinity && styleValue !== 0) {
        iframe.style[style] = typeof styleValue === 'number' ? `${styleValue}px` : styleValue;
        log(iframeId, `Set ${style} = ${iframe.style[style]}`);
      }
    }

    if (settings.get(iframeId).minHeight > settings.get(iframeId).maxHeight) {
      throw new Error('Value for minHeight can not be greater than maxHeight');
    }

    addStyle('maxHeight');
    addStyle('minHeight');
  }

  /**
   * Hands the frame the object other page script uses to drive it. Its
   * presence is also how setupIFrame knows this frame is already adopted, so
   * a navigation inside the frame does not arm a second set of listeners.
   *
   * @param {Element} iframe the frame being adopted
   * @param {string} iframeId the id the frame is keyed by
   * @returns {void}
   */
  function setupIFrameObject(iframe, iframeId) {
    iframe.iFrameResizer = {
      /**
       * Asks the frame to measure itself and report again. Ignored while the
       * frame has paused auto-resizing, which is how the Tunnel Manager holds
       * its own height while it shows the "router is down" overlay.
       *
       * @returns {void}
       */
      resize: () => {
        const entry = settings.get(iframeId);
        if (entry && entry.autoResize) {trigger('Window resize', 'resize', iframe, iframeId, true);}
      },

      /**
       * Scrolls the framed document to an anchor it holds.
       *
       * @param {string} anchor the element id to move to
       * @returns {void}
       */
      moveToAnchor: anchor => trigger('Move to anchor', `moveToAnchor:${anchor}`, iframe, iframeId)
    };
  }

  /**
   * Arms the listeners one adopted frame needs, then starts the handshake.
   *
   * Two triggers are needed because the frame may not have finished loading
   * yet, and because the load event also fires on every navigation inside the
   * frame, which is how a framed page that changes its own URL is re-sized.
   *
   * @param {Element} iframe the frame being adopted
   * @param {string} iframeId the id the frame is keyed by
   * @returns {void}
   */
  function init(iframe, iframeId) {
    function iFrameLoaded() {
      trigger('iFrame.onload', createInitMsg(iframeId), iframe, iframeId, true);
    }

    if (iframe.parentNode) {
      new MutationObserver(mutations => {
        for (const mutation of mutations) {
          for (const removed of mutation.removedNodes) {
            if (removed === iframe) {closeIFrame(iframe);}
          }
        }
      }).observe(iframe.parentNode, {childList: true});
    }

    iframe.addEventListener('load', iFrameLoaded);
    trigger('init', createInitMsg(iframeId), iframe, iframeId, true);
  }

  /**
   * Adopts one iframe element, unless it is already adopted.
   *
   * @param {Element} iframe the frame to adopt
   * @param {Object} [options] the settings for this frame
   * @returns {void}
   */
  function setupIFrame(iframe, options) {
    const needsId = iframe.id === '';
    const iframeId = ensureHasId(iframe, options);

    if (settings.has(iframeId) && 'iFrameResizer' in iframe) {return;}

    processOptions(iframe, options, iframeId);

    if (needsId) {log(iframeId, `Added missing iframe ID: ${iframeId} (${iframe.src})`);}

    setLimits(iframeId, iframe);
    init(iframe, iframeId);
    setupIFrameObject(iframe, iframeId);
  }

  /**
   * Watches the page for things that change every frame's height and asks all
   * of them to re-measure, once.
   *
   * @returns {void}
   */
  function setupEventListeners() {
    function resizeIFrames() {
      debounce(() => sendTriggerMsg('Window resize'), DEBOUNCE_MS);
    }

    function tabVisible() {
      if (document.visibilityState !== 'hidden') {
        debounce(() => sendTriggerMsg('Tab visible'), DEBOUNCE_MS);
      }
    }

    window.addEventListener('message', iFrameListener, false);
    window.addEventListener('resize', resizeIFrames, false);
    document.addEventListener('visibilitychange', tabVisible, false);
  }

  /**
   * Builds the public entry point.
   *
   * @returns {Function} iFrameResize
   */
  function factory() {
    let iFrames;

    function initOne(options, element) {
      if (!element.tagName) {throw new TypeError('Object is not a valid DOM element');}
      if ('IFRAME' !== element.tagName.toUpperCase()) {
        throw new TypeError(`Expected <IFRAME> tag, found <${element.tagName}>`);
      }

      setupIFrame(element, options);
      iFrames.push(element);
    }

    setupEventListeners();

    /**
     * Adopts frames matching a selector, or one element.
     *
     * @param {Object} [options] the settings for the frames
     * @param {string|Element} [target] a selector, an element, or nothing for
     *   every iframe on the page
     * @returns {Element[]} the frames adopted by this call
     */
    return function iFrameResizeF(options, target) {
      iFrames = [];

      if (target === undefined || typeof target === 'string') {
        document.querySelectorAll(target || 'iframe').forEach(element => initOne(options, element));
      } else if (typeof target === 'object') {
        initOne(options, target);
      } else {
        throw new TypeError(`Unexpected data type (${typeof target})`);
      }

      return iFrames;
    };
  }

  // The guard keeps a page that loads this file twice from arming a second set
  // of window listeners.
  window.iFrameResize = window.iFrameResize || factory();
})();
