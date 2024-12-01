/* I2P+ Lightbox for I2PSnark by dr|z3d */
/* Based on jsOnlyLightbox by Felix Hagspiel */
/* https://github.com/felixhagspiel/jsOnlyLightbox */

const snarkFiles = document.getElementById("snarkFiles");

class Lightbox {
  constructor() {
    if (!snarkFiles) return;
    this.prefix = "lb";
    this.data_attr = `data-${this.prefix}`;
    this.body = document.body;
    this.parentDoc = window.parent.document;
    this.currGroup = this.currThumbnail = null;
    this.currImages = [];
    this.isOpen = false;
    this.currImage = {};
    this.opt = {};
    this.intervalId = null;
    this.slideshowDelay = 5000;
    this.initialize();
  }

  initialize() {
    this.box = this.createEl("div", this.prefix);
    this.body.appendChild(this.box);
    this.playPauseContainer = this.createControls();
    this.addEventListeners();
  }

  createEl(tag, id) {
    const el = document.createElement(tag);
    el.id = id;
    return el;
  }

  createButton(text, id, callback) {
    const btn = this.createEl("span", id);
    btn.textContent = text;
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      callback();
    });
    return btn;
  }

  createControls() {
    const container = this.createEl("div", `${this.prefix}-playpause`);
    const controls = [
      { text: "<", id: `${this.prefix}-prev`, action: this.prev.bind(this) },
      { text: ">", id: `${this.prefix}-next`, action: this.next.bind(this) },
      { text: "Play", id: `${this.prefix}-play`, action: this.play.bind(this) },
      { text: "Pause", id: `${this.prefix}-pause`, action: this.pause.bind(this) },
    ];

    controls.forEach(({ text, id, action }) => {
      const btn = this.createButton(text, id, action);
      if (id === `${this.prefix}-prev` || id === `${this.prefix}-next`) { this.box.appendChild(btn); }
      else { container.appendChild(btn); }
      if (id === `${this.prefix}-prev`) this.prevBtn = btn;
      if (id === `${this.prefix}-next`) this.nextBtn = btn;
      if (id === `${this.prefix}-play`) this.playBtn = btn;
      if (id === `${this.prefix}-pause`) this.pauseBtn = btn;
    });

    this.box.appendChild(container);
    return container;
  }

  load(opt = {}) {
    this.setOpt(opt);
    this.thumbnails = [...document.querySelectorAll(".thumb")];
    this.thumbnails.forEach((thumbnail, index) => {
      thumbnail.setAttribute(`${this.data_attr}-index`, index);
      this.addThumbnailClickHandler(thumbnail);
    });
  }

  setOpt(opt) {
    const defaults = { preload: true, maxImgSize: .75 };
    this.opt = { ...defaults, ...opt };
    this.box.addEventListener("click", this.close.bind(this));
  }

  addThumbnailClickHandler(thumbnail) {
    thumbnail.addEventListener("click", (e) => {
      e.preventDefault();
      this.currGroup = thumbnail.getAttribute(`${this.data_attr}-group`) || "";
      this.currThumbnail = thumbnail;
      this.openBox(thumbnail);
    });
  }

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

  onImageLoad() {
    this.isOpen = true;
    this.resize();
    this.box.classList.add("active");
    if (this.currImages.length > 1) { this.toggleControlButtons(); }
    this.repositionControls();
  }

  toggleControlButtons() {
    if (this.prevBtn) this.prevBtn.classList.add("active");
    if (this.nextBtn) this.nextBtn.classList.add("active");
    this.preload();
    if (this.playBtn) this.playBtn.classList.toggle("active", !this.intervalId);
    if (this.pauseBtn) this.pauseBtn.classList.toggle("active", !!this.intervalId);
  }

  preload() {
    if (!this.currGroup) return;
    const currIndex = this.thumbnails.findIndex((thumb) => thumb === this.currThumbnail);
    const nextThumb = this.thumbnails[(currIndex + 1) % this.thumbnails.length];
    const prevThumb = this.thumbnails[(currIndex - 1 + this.thumbnails.length) % this.thumbnails.length];
    new Image().src = nextThumb.getAttribute(this.data_attr) || nextThumb.src;
    new Image().src = prevThumb.getAttribute(this.data_attr) || prevThumb.src;
  }

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
      maxHeight: `${maxHeight}px`,
    });
  }

  close() {
    this.isOpen = false;
    this.body.classList.remove("lightbox");
    this.box.classList.remove("active");
    this.currImage.img.remove();
    this.resetParentStyles();
    this.cleanupObserversAndListeners();
    this.pause();
  }

  adjustPosition() {
    if (this.isIframed()) {
      this.parentDoc.body.style.overflow = "hidden";
      this.parentDoc.body.style.contain = "paint";
      this.parentDoc.documentElement.style.overflow = "hidden";
      this.parentDoc.documentElement.classList.add("lightbox");
      this.box.style.height = `${window.parent.innerHeight}px`;
    } else {this.box.style.height = "100vh";}
    window.scrollTo(0,0);
  }

  resetParentStyles() {
    if (this.isIframed()) {
     this.parentDoc.documentElement.classList.remove("lightbox");
     this.parentDoc.body.removeAttribute("style");
     this.parentDoc.documentElement.removeAttribute("style");
    }
  }

  cleanupObserversAndListeners() {
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    }
  }

  play() {
    if (this.intervalId) return;
    this.intervalId = setInterval(() => this.next(), this.slideshowDelay);
    this.togglePlayPauseButtons();
    this.body.classList.add("slideshow");
  }

  pause() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
    this.togglePlayPauseButtons(true);
    this.body.classList.remove("slideshow");
  }

  togglePlayPauseButtons(isPaused = false) {
    if (this.playBtn) this.playBtn.classList.toggle("active", isPaused);
    if (this.pauseBtn) this.pauseBtn.classList.toggle("active", !isPaused);
    if (this.prevBtn) this.prevBtn.classList.toggle("active", !isPaused);
    if (this.nextBtn) this.nextBtn.classList.toggle("active", !isPaused);
  }

  addEventListeners() { this.addResizeEventListener(); }

  addResizeEventListener() {
    window.addEventListener("resize", () => {
      if (this.isOpen) {
        this.resize();
        this.repositionControls();
      }
    });
  }

  next() {
    if (!this.currGroup) return;
    this.currImage.img.remove();
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumbnail) + 1;
    if (this.currImages[pos]) { this.currThumbnail = this.currImages[pos]; }
    else { this.currThumbnail = this.currImages[0]; }
    this.openBox(this.currThumbnail);
  }

  prev() {
    if (!this.currGroup) return;
    this.currImage.img.remove();
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumbnail) - 1;
    if (this.currImages[pos]) { this.currThumbnail = this.currImages[pos]; }
    else { this.currThumbnail = this.currImages[this.currImages.length - 1]; }
    this.openBox(this.currThumbnail);
  }

  isIframed() {
    return document.documentElement.classList.contains("iframed") || window.top !== window.self;
  }

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