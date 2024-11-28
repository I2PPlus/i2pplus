/* I2P+ Lightbox for I2PSnark by dr|z3d */
/* Based on jsOnlyLightbox by Felix Hagspiel */
/* https://github.com/felixhagspiel/jsOnlyLightbox */

class Lightbox {
  constructor() {
    if (!snarkFiles) return;
    this._const_name = "lb";
    this._const_dataattr = "data-" + this._const_name;
    this.body = document.body;
    this.currGroup = null;
    this.currThumbnail = null;
    this.currImages = [];
    this.isOpen = false;
    this.currImage = {};
    this.opt = {};
    this.box = document.createElement("div");
    this.wrapper = document.createElement("div");
    this.thumbnails = [];
    this.resizeObserver = null;
  }

  initTemplate() {
    this.box.id = this._const_name;
    this.wrapper.id = this._const_name + "-wrap";
    this.box.appendChild(this.wrapper);
    this.body.appendChild(this.box);
    if (this.opt.controls) {
      const prevBtn = document.createElement("button");
      prevBtn.textContent = "<";
      prevBtn.id = this._const_name + "-prev";
      prevBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.prev();
      });
      if (!document.getElementById(this._const_name + "-prev")) {
        this.box.appendChild(prevBtn);
      }
      const nextBtn = document.createElement("button");
      nextBtn.textContent = ">";
      nextBtn.id = this._const_name + "-next";
      nextBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.next();
      });
      if (!document.getElementById(this._const_name + "-next")) {
        this.box.appendChild(nextBtn);
      }
    }
    if (!this.opt.hideCloseBtn) {
      const closeBtn = document.createElement("button");
      closeBtn.textContent = "X";
      closeBtn.id = this._const_name + "-close";
      closeBtn.addEventListener("click", () => {
        this.close();
      });
      if (!document.getElementById(this._const_name + "-close")) {
        this.box.appendChild(closeBtn);
      }
    }
    const boxStyles = {
      display: "none",
    };
    Object.assign(this.box.style, boxStyles);
  }

  loadEventListeners() {
    document.addEventListener("DOMContentLoaded", () => {
      if (snarkFiles) {
        this.load();
      }
    });
  }

  load(opt = {}) {
    this.setOpt(opt);
    this.initTemplate();
    this.addResizeEventListener();
    const thumbnails = document.querySelectorAll(".thumb");
    thumbnails.forEach((thumbnail, index) => {
      thumbnail.setAttribute(`${this._const_dataattr}-index`, index);
      this.thumbnails.push(thumbnail);
      this.addThumbnailClickHandler(thumbnail);
    });
  }

  repositionControls() {
    if (this.opt.responsive && this.box.querySelector(".lb-prev") && this.box.querySelector(".lb-next")) {
      const btns = this.box.querySelectorAll(".lb-prev, .lb-next");
      const btnTop = (this.isIframed() ? window.parent.innerHeight : window.innerHeight) / 2 - (btns[0].offsetHeight / 2);
      btns.forEach(btn => { btn.style.top = `${btnTop}px`; });
    }
  }

  setOpt(opt) {
    this.opt = {
      boxId: opt.boxId || false,
      controls: opt.controls !== undefined ? opt.controls : true,
      prevImg: typeof opt.prevImg === "string" ? opt.prevImg : false,
      nextImg: typeof opt.nextImg === "string" ? opt.nextImg : false,
      hideCloseBtn: opt.hideCloseBtn || false,
      closeOnClick: typeof opt.closeOnClick === "boolean" ? opt.closeOnClick : true,
      nextOnClick: opt.nextOnClick !== undefined ? opt.nextOnClick : true,
      loadingAnimation: opt.loadingAnimation !== undefined ? opt.loadingAnimation : true,
      animElCount: opt.animElCount || 4,
      preload: opt.preload !== undefined ? opt.preload : true,
      carousel: opt.carousel !== undefined ? opt.carousel : true,
      animation: typeof opt.animation === "number" || opt.animation === false ? opt.animation : 400,
      responsive: opt.responsive !== undefined ? opt.responsive : true,
      maxImgSize: opt.maxImgSize || 0.8,
      keyControls: opt.keyControls !== undefined ? opt.keyControls : true,
      hideOverflow: opt.hideOverflow !== undefined ? opt.hideOverflow : true,
      onopen: opt.onopen || false,
      onclose: opt.onclose || false,
      onload: opt.onload || false,
      onresize: opt.onresize || false,
      onloaderror: opt.onloaderror || false,
      onimageclick: typeof opt.onimageclick === "function" ? opt.onimageclick : false,
    };
    if (this.opt.boxId) {
      this.box = document.getElementById(this.opt.boxId) || this.box;
    }
    if (!this.opt.hideCloseBtn) {
      const closeBtn = document.createElement("span");
      closeBtn.className = "lb-close";
      closeBtn.id = this._const_name + "-close";
      closeBtn.textContent = "X";
      closeBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.close();
      });
      if (!document.getElementById(this._const_name + "-close")) {
        this.box.appendChild(closeBtn);
      }
    }
    if (this.opt.closeOnClick) {
      this.box.addEventListener("click", (e) => {
        e.stopPropagation();
        this.close();
      });
    }
  }

  addThumbnailClickHandler(thumbnail) {
    if (!thumbnail) {return;}
    thumbnail.addEventListener("click", (e) => {
      e.stopPropagation();
      e.preventDefault();
      this.currGroup = thumbnail.getAttribute(`${this._const_dataattr}-group`) || false;
      this.currThumbnail = thumbnail;
      this.openBox(thumbnail);
    });
  }

  openBox(el) {
    if (!el) return;

    if (this.wrapper.firstChild) {this.wrapper.removeChild(this.wrapper.firstChild);}
    window.scrollTo(0,0);

    this.currImage.img = new Image();
    this.currThumbnail = el;
    const src = el.getAttribute(this._const_dataattr) || el.src;
    this.currImage.img.src = src;

    const boxStyles = {
      display: "flex",
      justifyContent: "center",
      alignItems: "center",
    };
    Object.assign(this.box.style, boxStyles);

    this.currImages = Array.from(document.querySelectorAll(`[${this._const_dataattr}-group="${this.currGroup}"]`));
    const currImageStyles = {
      display: "block"
    };
    Object.assign(this.currImage.img.style, currImageStyles);

    this.currImage.img.onload = () => {
      this.isOpen = true;
      this.resize();
      this.box.id = `${this._const_name}`;
      this.box.classList.add("active");

      if (this.currImages.length > 1) {
        const prev = document.getElementById(this._const_name + "-prev");
        const next = document.getElementById(this._const_name + "-next");
        prev.style.display = "inline-block";
        next.style.display = "inline-block";
      }

      this.repositionControls();

      if (this.opt.onload) this.opt.onload();
      this.preload();
    };

    this.wrapper.appendChild(this.currImage.img);
    this.addImageClickHandler();

    this.isIframed();
    if (this.isIframed()) {
      this.adjustForIframe();
    }

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
    if (!this.currGroup) return;
    const currIndex = this.thumbnails.findIndex((thumbnail) => thumbnail === this.currThumbnail);
    const nextIndex = (currIndex + 1) % this.thumbnails.length;
    const prevIndex = (currIndex - 1 + this.thumbnails.length) % this.thumbnails.length;
    const nextThumbnail = this.thumbnails[nextIndex];
    const prevThumbnail = this.thumbnails[prevIndex];
    const nextSrc = nextThumbnail.getAttribute(this._const_dataattr) || nextThumbnail.src;
    const prevSrc = prevThumbnail.getAttribute(this._const_dataattr) || prevThumbnail.src;
    const nextImg = new Image();
    const prevImg = new Image();
    nextImg.src = nextSrc;
    prevImg.src = prevSrc;
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

    const currImgStyles = {
      width: `auto`,
      height: `${Math.min(Math.floor(newImgHeight), 600)}px`,
      maxWidth: `${maxWidth}px`,
      maxHeight: `${maxHeight}px`,
    };
    Object.assign(this.currImage.img.style, currImgStyles);

    if (this.opt.onresize) this.opt.onresize(this.currImage);
  }

  close() {
    this.isOpen = false;
    this.box.style.display = "none";
    this.box.classList.remove("active");
    this.wrapper.innerHTML = "";
    if (this.opt.onclose) this.opt.onclose();
    this.body.style.overflow = "auto";
    this.removeEventListeners();
    if (this.isIframed()) {
      window.parent.document.body.removeAttribute("style");
      window.parent.document.documentElement.removeAttribute("style");
    }
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    }
  }

  next() {
    if (!this.currGroup) return;
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumbnail) + 1;
    if (this.currImages[pos]) {
      this.currThumbnail = this.currImages[pos];
    } else if (this.opt.carousel) {
      this.currThumbnail = this.currImages[0];
    } else {
      return;
    }
    this.openBox(this.currThumbnail);
  }

  prev() {
    if (!this.currGroup) return;
    const pos = this.currImages.findIndex((thumbnail) => thumbnail === this.currThumbnail) - 1;
    if (this.currImages[pos]) {
      this.currThumbnail = this.currImages[pos];
    } else if (this.opt.carousel) {
      this.currThumbnail = this.currImages[this.currImages.length - 1];
    } else {
      return;
    }
    this.openBox(this.currThumbnail);
  }

  getPos(thumbnail) {
    return this.thumbnails.findIndex((t) => t === thumbnail);
  }

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
    const prevBtn = this.box.querySelector("#lb-prev");
    if (prevBtn) {prevBtn.removeEventListener("click", this.prev.bind(this));}
    const nextBtn = this.box.querySelector("#lb-next");
    if (nextBtn) {nextBtn.removeEventListener("click", this.next.bind(this));}
    const closeBtn = this.box.querySelector("#lb-close");
    if (closeBtn) {closeBtn.removeEventListener("click", this.close.bind(this));}
    this.box.removeEventListener("click", this.close.bind(this));
  }

  isIframed() {
    if (document.documentElement.classList.contains("iframed") || window.top !== window.self) {return true;}
    return false;
  }

  adjustForIframe() {
    const parentDocument = window.parent.document;
    this.box.style.height = `${window.parent.innerHeight}px`;
    parentDocument.body.style.overflow = "hidden";
    parentDocument.documentElement.style.overflow = "hidden";
    parentDocument.body.style.contain = "paint";
    window.parent.scrollTo(0,0);
  }
}

export { Lightbox };