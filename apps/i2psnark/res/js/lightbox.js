/**
 * @file lightbox.js - Image lightbox for I2PSnark file viewer.
 * @description A self-contained lightbox class for viewing thumbnail images in a fullscreen
 * overlay. Supports grouped image navigation (prev/next), slideshow playback with configurable
 * delay, responsive resizing, iframe-aware positioning, and preloading of adjacent images.
 * Based on jsOnlyLightbox by Felix Hagspiel.
 * @author dr|z3d
 * @see {@link https://github.com/felixhagspiel/jsOnlyLightbox}
 */

/**
 * @type {?HTMLElement}
 * @description The #snarkFiles container element. Lightbox initialization is skipped if absent.
 */
const snarkFiles = document.getElementById("snarkFiles");

/**
 * @class Lightbox
 * @description Provides a fullscreen image viewing experience with navigation controls,
 * slideshow playback, and responsive image sizing. Operates within iframes and supports
 * grouped image galleries.
 * @example
 * const lightbox = new Lightbox();
 * lightbox.load({ maxImgSize: 0.8 });
 */
class Lightbox {
  /**
   * @constructor
   * @description Initializes the Lightbox instance. Sets up internal state for image tracking,
   * slideshow intervals, and option handling. Calls initialize() to create the lightbox DOM.
   */
  constructor() {
    if (!snarkFiles) return;
    this.prefix = "lb";
    this.data_attr = `data-${this.prefix}`;
    this.body = document.body;
    this.parentDoc = window.parent.document;
    this.currGroup = this.currThumb = null;
    this.currImages = [];
    this.isOpen = false;
    this.currImage = {};
    this.opt = {};
    this.intervalId = null;
    this.slideshowDelay = 5000;
    this.initialize();
  }

  /**
   * @method initialize
   * @description Creates the lightbox container element and attaches it to the document body.
   * @returns {void}
   */
  initialize() {
    this.box = this.createEl("div", this.prefix);
    this.body.appendChild(this.box);
    this.addEventListeners();
  }

  /**
   * @method createEl
   * @description Creates a new DOM element with the specified tag and id.
   * @param {string} tag - The HTML tag name for the element.
   * @param {string} id - The id attribute to assign to the element.
   * @returns {HTMLElement} The newly created element.
   */
  createEl(tag, id) {
    const el = document.createElement(tag);
    el.id = id;
    return el;
  }

  /**
   * @method createButton
   * @description Creates a clickable button element with text content and a click callback.
   * Stops event propagation on click.
   * @param {string} text - The text content for the button.
   * @param {string} id - The id attribute for the button element.
   * @param {Function} callback - The function to call when the button is clicked.
   * @returns {HTMLSpanElement} The created button element.
   */
  createButton(text, id, callback) {
    const btn = this.createEl("span", id);
    btn.textContent = text;
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      callback();
    });
    return btn;
  }

  /**
   * @method createControls
   * @description Creates the slideshow control buttons (prev, next, play, pause) and appends
   * them to the lightbox container. Navigation buttons are added directly to the box;
   * play/pause are grouped in a container.
   * @returns {HTMLElement} The play/pause control container element.
   */
  createControls() {
    const container = this.createEl("div", `${this.prefix}-playpause`);
    const controls = [
      { text: "<", id: `${this.prefix}-prev`, action: this.prev.bind(this) },
      { text: ">", id: `${this.prefix}-next`, action: this.next.bind(this) },
      { text: "Play", id: `${this.prefix}-play`, action: this.play.bind(this) },
      { text: "Pause", id: `${this.prefix}-pause`, action: this.pause.bind(this) }
    ];

    controls.forEach(({ text, id, action }) => {
      const btn = this.createButton(text, id, action);
      if (id === `${this.prefix}-prev` || id === `${this.prefix}-next`) { this.box.appendChild(btn); }
      else { container.appendChild(btn); }
      if (id === `${this.prefix}-prev`) { this.prevBtn = btn; }
      if (id === `${this.prefix}-next`) { this.nextBtn = btn; }
      if (id === `${this.prefix}-play`) { this.playBtn = btn; }
      if (id === `${this.prefix}-pause`) { this.pauseBtn = btn; }
    });

    this.box.appendChild(container);
    return container;
  }

  /**
   * @method load
   * @description Loads thumbnail elements from the document, assigns index data attributes,
   * attaches click handlers, and creates slideshow controls if there are multiple thumbnails.
   * @param {Object} [opt={}] - Configuration options merged with defaults.
   * @param {boolean} [opt.preload=true] - Whether to preload adjacent images.
   * @param {number} [opt.maxImgSize=0.75] - Maximum image size as a fraction of viewport.
   * @returns {void}
   */
  load(opt = {}) {
    this.setOpt(opt);
    this.thumbnails = [...document.querySelectorAll(".thumb")];
    this.thumbnails.forEach((thumbnail, index) => {
      thumbnail.setAttribute(`${this.data_attr}-index`, index);
      this.addThumbnailClickHandler(thumbnail);
    });
    if (this.thumbnails.length > 1) {
      this.playPauseContainer = this.createControls();
    }
  }

  /**
   * @method setOpt
   * @description Sets lightbox options by merging provided options with defaults, and
   * attaches a click-to-close handler on the lightbox container.
   * @param {Object} [opt={}] - User-provided options to override defaults.
   * @returns {void}
   */
  setOpt(opt) {
    const defaults = { preload: true, maxImgSize: .75 };
    this.opt = { ...defaults, ...opt };
    this.box.addEventListener("click", this.close.bind(this));
  }

  /**
   * @method addThumbnailClickHandler
   * @description Attaches a click handler to a thumbnail that opens the lightbox with
   * the appropriate group and image.
   * @param {HTMLElement} thumbnail - The thumbnail image element to attach the handler to.
   * @returns {void}
   */
  addThumbnailClickHandler(thumbnail) {
    thumbnail.addEventListener("click", (e) => {
      e.preventDefault();
      this.currGroup = thumbnail.getAttribute(`${this.data_attr}-group`) || "";
      this.currThumb = thumbnail;
      this.openBox(thumbnail);
    });
  }

  /**
   * @method openBox
   * @description Opens the lightbox for the given element. Creates a new Image, sets its
   * source from the data attribute or element src, displays the lightbox container, and
   * triggers resize and positioning on image load.
   * @param {HTMLElement} el - The thumbnail element whose image to display.
   * @returns {void}
   */
  openBox(el) {
    if (!el) return;
    document.body.classList.add("lightbox");
    window.scrollTo(0, 0);
    this.currImage.img = new Image();
    const src = el.getAttribute(this.data_attr) || el.src;
    this.currImage.img.src = src;
    this.box.style.display = "flex";
    this.currImages = Array.from(document.querySelectorAll(`[${this.data_attr}-group="${this.currGroup}"]`));
    this.currImage.img.onload = () => this.onImageLoad();
    this.box.appendChild(this.currImage.img);
    this.adjustPosition();
    this.setupResizeObserver();
  }

  /**
   * @method onImageLoad
   * @description Called when the lightbox image finishes loading. Marks the lightbox as open,
   * resizes the image, shows navigation controls if multiple images exist, and repositions them.
   * @returns {void}
   */
  onImageLoad() {
    this.isOpen = true;
    this.resize();
    this.box.classList.add("active");
    if (this.currImages.length > 1) { this.toggleControlButtons(); }
    this.repositionControls();
  }

  /**
   * @method toggleControlButtons
   * @description Shows the navigation (prev/next) buttons and toggles play/pause button
   * visibility based on slideshow state. Triggers image preloading.
   * @returns {void}
   */
  toggleControlButtons() {
    if (this.prevBtn) this.prevBtn.classList.add("active");
    if (this.nextBtn) { this.nextBtn.classList.add("active"); }
    this.preload();
    if (this.playBtn) { this.playBtn.classList.toggle("active", !this.intervalId); }
    if (this.pauseBtn) { this.pauseBtn.classList.toggle("active", !!this.intervalId); }
  }

  /**
   * @method preload
   * @description Preloads the next and previous images in the current group to improve
   * navigation responsiveness.
   * @returns {void}
   */
  preload() {
    if (!this.currGroup) return;
    const currIndex = this.thumbnails.findIndex((thumb) => thumb === this.currThumb);
    const nextThumb = this.thumbnails[(currIndex + 1) % this.thumbnails.length];
    const prevThumb = this.thumbnails[(currIndex - 1 + this.thumbnails.length) % this.thumbnails.length];
    new Image().src = nextThumb.getAttribute(this.data_attr) || nextThumb.src;
    new Image().src = prevThumb.getAttribute(this.data_attr) || prevThumb.src;
  }

  /**
   * @method resize
   * @description Resizes the current lightbox image to fit within the viewport while
   * maintaining aspect ratio, constrained by the maxImgSize option.
   * @returns {void}
   */
  resize() {
    if (!this.currImage.img) return;
    const maxWidth = window.innerWidth * this.opt.maxImgSize;
    const maxHeight = window.innerHeight * this.opt.maxImgSize;
    const imgRatio = this.currImage.img.naturalWidth / this.currImage.img.naturalHeight;
    let newImgWidth = maxWidth;
    let newImgHeight = maxWidth / imgRatio;
    if (newImgHeight > maxHeight) {
      newImgHeight = maxHeight;
      newImgWidth = maxHeight * imgRatio;
    }
    Object.assign(this.currImage.img.style, {
      height: `${newImgHeight}px`,
      maxWidth: `${maxWidth}px`,
      maxHeight: `${maxHeight}px`
    });
  }

  /**
   * @method close
   * @description Closes the lightbox, removes the active class, removes the image from the DOM,
   * resets parent styles, cleans up observers, and pauses any active slideshow.
   * @returns {void}
   */
  close() {
    this.isOpen = false;
    this.body.classList.remove("lightbox");
    this.box.classList.remove("active");
    this.currImage.img.remove();
    this.resetParentStyles();
    this.cleanupObserversAndListeners();
    this.pause();
  }

  /**
   * @method adjustPosition
   * @description Adjusts the lightbox positioning for iframe or standalone mode. In iframe
   * mode, locks scrolling on the parent document. Sets the lightbox height to viewport height.
   * @returns {void}
   */
  adjustPosition() {
    if (this.isIframed()) {
      this.parentDoc.body.style.overflow = "hidden";
      this.parentDoc.body.style.contain = "paint";
      this.parentDoc.documentElement.style.overflow = "hidden";
      this.parentDoc.documentElement.classList.add("lightbox", "fullscreen");
      this.box.style.height = `${window.parent.innerHeight}px`;
    } else {this.box.style.height = "100vh";}
    window.scrollTo(0,0);
  }

  /**
   * @method resetParentStyles
   * @description Restores parent document styles when the lightbox is closed in iframe mode.
   * Removes lightbox and fullscreen classes and clears inline styles.
   * @returns {void}
   */
  resetParentStyles() {
    if (this.isIframed()) {
     this.parentDoc.documentElement.classList.remove("lightbox", "fullscreen");
     this.parentDoc.body.removeAttribute("style");
     this.parentDoc.documentElement.removeAttribute("style");
    }
  }

  /**
   * @method cleanupObserversAndListeners
   * @description Disconnects the ResizeObserver if active and nullifies the reference.
   * @returns {void}
   */
  cleanupObserversAndListeners() {
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    }
  }

  /**
   * @method play
   * @description Starts the slideshow by setting an interval that advances to the next image
   * every slideshowDelay milliseconds. Updates button states.
   * @returns {void}
   */
  play() {
    if (this.intervalId) return;
    this.intervalId = setInterval(() => this.next(), this.slideshowDelay);
    this.togglePlayPauseButtons();
    this.body.classList.add("slideshow");
  }

  /**
   * @method pause
   * @description Pauses the slideshow by clearing the interval and resetting the interval ID.
   * Updates button states to reflect paused mode.
   * @returns {void}
   */
  pause() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
    this.togglePlayPauseButtons(true);
    this.body.classList.remove("slideshow");
  }

  /**
   * @method togglePlayPauseButtons
   * @description Toggles the active state of play, pause, prev, and next buttons based
   * on whether the slideshow is paused.
   * @param {boolean} [isPaused=false] - Whether the slideshow is currently paused.
   * @returns {void}
   */
  togglePlayPauseButtons(isPaused = false) {
    if (this.playBtn) { this.playBtn.classList.toggle("active", isPaused); }
    if (this.pauseBtn) { this.pauseBtn.classList.toggle("active", !isPaused); }
    if (this.prevBtn) { this.prevBtn.classList.toggle("active", !isPaused); }
    if (this.nextBtn) { this.nextBtn.classList.toggle("active", !isPaused); }
  }

  /**
   * @method addEventListeners
   * @description Registers all event listeners, currently only the resize listener.
   * @returns {void}
   */
  addEventListeners() { this.addResizeEventListener(); }

  /**
   * @method addResizeEventListener
   * @description Attaches a passive resize listener to the window that resizes and
   * repositions controls when the lightbox is open.
   * @returns {void}
   */
  addResizeEventListener() {
    window.addEventListener("resize", () => {
      if (this.isOpen) {
        this.resize();
        this.repositionControls();
      }
    }, {passive: true});
  }

  /**
   * @method next
   * @description Advances to the next image in the current group. Wraps around to the
   * first image if at the end of the group.
   * @returns {void}
   */
  next() {
    if (!this.currGroup) return;
    this.currImage.img.remove();
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumb) + 1;
    if (this.currImages[pos]) { this.currThumb = this.currImages[pos]; }
    else { this.currThumb = this.currImages[0]; }
    this.openBox(this.currThumb);
  }

  /**
   * @method prev
   * @description Goes to the previous image in the current group. Wraps around to the
   * last image if at the beginning of the group.
   * @returns {void}
   */
  prev() {
    if (!this.currGroup) return;
    this.currImage.img.remove();
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumb) - 1;
    if (this.currImages[pos]) { this.currThumb = this.currImages[pos]; }
    else { this.currThumb = this.currImages[this.currImages.length - 1]; }
    this.openBox(this.currThumb);
  }

  /**
   * @method isIframed
   * @description Checks whether the page is running inside an iframe by inspecting
   * the "iframed" class on the html element or comparing window.top to window.self.
   * @returns {boolean} True if the page is in an iframe context.
   */
  isIframed() {
    return document.documentElement.classList.contains("iframed") || window.top !== window.self;
  }

  /**
   * @method setupResizeObserver
   * @description Creates a ResizeObserver on the document body (or parent body in iframe mode)
   * that triggers resize and control repositioning when the container size changes.
   * @returns {void}
   */
  setupResizeObserver() {
    this.resizeObserver = new ResizeObserver(entries => {
      entries.forEach(entry => {
        if (entry.target === (this.isIframed() ? this.parentDoc.body : document.body) && this.isOpen) {
          this.resize();
          this.repositionControls();
        }
      });
    });
    this.resizeObserver.observe(this.isIframed() ? this.parentDoc.body : document.body);
  }

  /**
   * @method repositionControls
   * @description Vertically centers the prev and next navigation buttons relative to the
   * viewport (or parent viewport in iframe mode).
   * @returns {void}
   */
  repositionControls() {
    if (this.prevBtn && this.nextBtn) {
      const btnTop = (this.isIframed() ? window.parent.innerHeight : window.innerHeight) / 2 - (this.prevBtn.offsetHeight / 2);
      [this.prevBtn, this.nextBtn].forEach(btn => {
        btn.style.top = `${btnTop}px`;
      });
    }
  }
}

export { Lightbox };