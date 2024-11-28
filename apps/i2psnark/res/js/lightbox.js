/* I2P+ Lightbox for I2PSnark by dr|z3d */
/* Based on jsOnlyLightbox by Felix Hagspiel */
/* https://github.com/felixhagspiel/jsOnlyLightbox */

class Lightbox {
  constructor() {
    if (!snarkFiles) return;
    this.prefix = "lb";
    this.data_attr = `data-${this.prefix}`;
    this.body = document.body;
    this.currGroup = null;
    this.currThumbnail = null;
    this.currImages = [];
    this.isOpen = false;
    this.currImage = {};
    this.opt = {};
    this.box = document.createElement("div");
    this.wrap = document.createElement("div");
    this.thumbnails = [];
    this.resizeObserver = null;
  }

  initTemplate() {
    this.box.id = this.prefix;
    this.wrap.id = `${this.prefix}-wrap`;
    this.box.appendChild(this.wrap);
    this.body.appendChild(this.box);
    if (this.opt.controls) {
      this.prevBtn = document.createElement("button");
      this.prevBtn.textContent = "<";
      this.prevBtn.id = `${this.prefix}-prev`;
      this.prevBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.prev();
      });
      if (!document.getElementById(`${this.prefix}-prev`)) { this.box.appendChild(this.prevBtn); }
      this.nextBtn = document.createElement("button");
      this.nextBtn.textContent = ">";
      this.nextBtn.id = `${this.prefix}-next`;
      this.nextBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.next();
      });
      if (!document.getElementById(`${this.prefix}-next`)) { this.box.appendChild(this.nextBtn); }
    }
    const boxStyles = { display: "none" };
    Object.assign(this.box.style, boxStyles);
  }

  loadEventListeners() {
    document.addEventListener("DOMContentLoaded", () => {
      if (snarkFiles) { this.load(); }
    });
  }

  load(opt = {}) {
    this.setOpt(opt);
    this.initTemplate();
    this.addResizeEventListener();
    const thumbnails = document.querySelectorAll(".thumb");
    thumbnails.forEach((thumbnail, index) => {
      thumbnail.setAttribute(`${this.data_attr}-index`, index);
      this.thumbnails.push(thumbnail);
      this.addThumbnailClickHandler(thumbnail);
    });
  }

  repositionControls() {
    if (this.opt.responsive && this.prevBtn && this.nextBtn) {
      const btnTop = (this.isIframed() ? window.parent.innerHeight : window.innerHeight) / 2 - (this.prevBtn.offsetHeight / 2);
      [this.prevBtn, this.nextBtn].forEach(btn => btn.style.top = `${btnTop}px`);
    }
  }

  setOpt(opt) {
    this.opt = {
      controls: opt.controls !== undefined ? opt.controls : true,
      closeOnClick: typeof opt.closeOnClick === "boolean" ? opt.closeOnClick : true,
      nextOnClick: opt.nextOnClick !== undefined ? opt.nextOnClick : true,
      preload: opt.preload !== undefined ? opt.preload : true,
      carousel: opt.carousel !== undefined ? opt.carousel : true,
      responsive: opt.responsive !== undefined ? opt.responsive : true,
      maxImgSize: opt.maxImgSize !== undefined ? opt.maxImgSize : 0.8,
      onimageclick: typeof opt.onimageclick === "function" ? opt.onimageclick : false,
    };
    if (this.opt.closeOnClick) {
      this.box.addEventListener("click", (e) => {
        e.stopPropagation();
        this.close();
      });
    }
  }

  addThumbnailClickHandler(thumbnail) {
    thumbnail.addEventListener("click", (e) => {
      e.preventDefault();
      this.currGroup = thumbnail.getAttribute(`${this.data_attr}-group`) || '';
      this.currThumbnail = thumbnail;
      this.openBox(thumbnail);
    });
  }

  openBox(el) {
    if (!el) return;
    document.body.classList.add("lightbox");
    if (this.wrap.firstChild) { this.wrap.removeChild(this.wrap.firstChild); }
    window.scrollTo(0, 0);
    this.currImage.img = new Image();
    this.currThumbnail = el;
    const src = el.getAttribute(this.data_attr) || el.src;
    this.currImage.img.src = src;
    const boxStyles = { display: "flex", justifyContent: "center", alignItems: "center" };
    Object.assign(this.box.style, boxStyles);
    this.currImages = Array.from(document.querySelectorAll(`[${this.data_attr}-group="${this.currGroup}"]`));
    this.currImage.img.style.display = "block";
    this.currImage.img.onload = () => {
      this.isOpen = true;
      this.resize();
      this.box.id = `${this.prefix}`;
      this.box.classList.add("active");
      if (this.currImages.length > 1) {
        this.prevBtn.style.display = "inline-block";
        this.nextBtn.style.display = "inline-block";
        this.preload();
      }
      this.repositionControls();
    };
    this.wrap.appendChild(this.currImage.img);
    this.addImageClickHandler();
    this.isIframed();
    if (this.isIframed()) { this.adjustForIframe(); }
    this.resizeObserver = new ResizeObserver(entries => {
      entries.forEach(entry => {
        if (entry.target === (this.isIframed() ? window.parent.document.body : document.body)) {
          if (this.isOpen) {
            this.resize();
            this.repositionControls();
          }
        }
      });
    });
    this.resizeObserver.observe(this.isIframed() ? window.parent.document.body : document.body);
  }

  addImageClickHandler() {
    if (this.opt.onimageclick) {
      this.currImage.img.addEventListener("click", (e) => {
        e.stopPropagation();
        this.opt.onimageclick(this.currImage);
      });
    }
  }

  preload() {
    if (!this.currGroup) { return; }
    const currIndex = this.thumbnails.findIndex((thumbnail) => thumbnail === this.currThumbnail);
    const nextIndex = (currIndex + 1) % this.thumbnails.length;
    const prevIndex = (currIndex - 1 + this.thumbnails.length) % this.thumbnails.length;
    const nextThumbnail = this.thumbnails[nextIndex];
    const prevThumbnail = this.thumbnails[prevIndex];
    const nextSrc = nextThumbnail.getAttribute(this.data_attr) || nextThumbnail.src;
    const prevSrc = prevThumbnail.getAttribute(this.data_attr) || prevThumbnail.src;
    const nextImg = new Image();
    const prevImg = new Image();
    nextImg.src = nextSrc;
    prevImg.src = prevSrc;
  }

  resize() {
    if (!this.currImage.img) { return; }
    const maxWidth = window.innerWidth * this.opt.maxImgSize;
    const maxHeight = window.innerHeight * this.opt.maxImgSize;
    const imgRatio = this.currImage.img.naturalWidth / this.currImage.img.naturalHeight;
    let newImgWidth = maxWidth;
    let newImgHeight = maxWidth / imgRatio;
    if (newImgHeight > maxHeight) {
      newImgHeight = maxHeight;
      newImgWidth = maxHeight * imgRatio;
    }
    const currImgStyles = {
      width: `auto`,
      height: `${Math.min(Math.floor(newImgHeight), 600)}px`,
      maxWidth: `${maxWidth}px`,
      maxHeight: `${maxHeight}px`,
    };
    Object.assign(this.currImage.img.style, currImgStyles);
  }

  close() {
    this.isOpen = false;
    this.body.classList.remove("lightbox");
    this.body.style.overflow = "auto";
    this.box.classList.remove("active");
    this.box.style.display = "none";
    this.wrap.innerHTML = "";
    if (this.isIframed()) {
      const parentDocument = window.parent.document;
      parentDocument.documentElement.classList.remove("lightbox")
      parentDocument.body.removeAttribute("style");
      parentDocument.documentElement.removeAttribute("style");
    }
    this.removeEventListeners();
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    }
  }

  next() {
    if (!this.currGroup) { return; }
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumbnail) + 1;
    if (this.currImages[pos]) { this.currThumbnail = this.currImages[pos]; }
    else if (this.opt.carousel) { this.currThumbnail = this.currImages[0]; }
    else { return; }
    this.openBox(this.currThumbnail);
  }

  prev() {
    if (!this.currGroup) { return; }
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumbnail) - 1;
    if (this.currImages[pos]) { this.currThumbnail = this.currImages[pos]; }
    else if (this.opt.carousel) { this.currThumbnail = this.currImages[this.currImages.length - 1]; }
    else { return; }
    this.openBox(this.currThumbnail);
  }

  getPos(thumbnail) { return this.thumbnails.findIndex((t) => t === thumbnail); }

  addResizeEventListener() {
    window.addEventListener("resize", () => {
      if (this.isOpen) {
        this.resize();
        this.repositionControls();
        this.adjustForIframe();
      }
    });
  }

  removeEventListeners() {
    if (this.prevBtn) { this.prevBtn.removeEventListener("click", this.prev.bind(this)); }
    if (this.nextBtn) { this.nextBtn.removeEventListener("click", this.next.bind(this)); }
    this.box.removeEventListener("click", this.close.bind(this));
  }

  isIframed() {
    return document.documentElement.classList.contains("iframed") || window.top !== window.self;
  }

  adjustForIframe() {
    const parentDocument = window.parent.document;
    this.box.style.height = `${window.parent.innerHeight}px`;
    this.wrap.style.marginTop = "-60px";
    parentDocument.body.style.overflow = "hidden";
    parentDocument.documentElement.style.overflow = "hidden";
    parentDocument.body.style.contain = "paint";
    parentDocument.documentElement.classList.add("lightbox");
    window.parent.scrollTo(0, 0);
  }
}

export { Lightbox };