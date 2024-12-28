/* I2P+ deleteMail.js for Susimail by dr|z3d */
/* Ensures delete mail modal is visible on instantiation */

(function handleModal() {
  let overlay = document.getElementById("overlay");

  async function toggleModalStyles() {
    if (!overlay) { overlay = await createOverlay(); }

    const modal = document.getElementById("nukemail");
    const parentDoc = window.parent.document;
    const iframe = parentDoc.getElementById("susimailframe");
    let iframed = document.documentElement.classList.contains("iframed") || window.top != window.parent.top;
    let modalRect;
    let modalheight;
    let viewportHeight;

    if (modal) {
      viewportHeight = window.parent.innerHeight;
      modalRect = modal.getBoundingClientRect();
      modalHeight = modalRect.height;
      document.body.classList.add("modal");
      parentDoc.body.classList.add("modal");
      modal.style.top = (viewportHeight - modalHeight) / 3 + "px";
      window.scrollTo(0,0);
      if (iframed) {
        window.parent.scrollTo(0,0);
        iframe.style.maxHeight = (viewportHeight - 80) + "px";
        overlay.style.maxHeight = (viewportHeight - 88) + "px";
      } else {
        overlay.style.maxHeight = "100vh";
        document.body.style.height = "calc(100vh - 14px)";
      }
    } else {
      document.body.classList.remove("modal");
      document.body.style.overflow = null;
      document.body.style.height = null;
      if (iframed) {
        iframe.style.maxHeight = null;
        parentDoc.body.classList.remove("modal");
      }
    }

    const observer = new MutationObserver(async mutationsList => {
      let modalRemoved = false;
      for (const mutation of mutationsList) {
        if (mutation.type === "childList") {
          const modal = document.getElementById("nukemail");
          if (!modal) {
            modalRemoved = true;
            break;
          }
        }
      }
      if (modalRemoved) {
        document.body.style.overflow = null;
        iframed = document.documentElement.classList.contains("iframed");
        if (iframed) {iframe.style.maxHeight = null;}
        document.body.classList.remove("modal");
        if (iframed) {parentDoc.body.classList.remove("modal");}
      }
    });

    observer.observe(document.body, { childList: true, subtree: true, attributes: true });
    window.addEventListener("resize", () => {
      if (modal) {
        viewportHeight = window.parent.innerHeight;
        modal.style.top = "5%";
        if (parseInt(viewportHeight, 10) > 500) {
          modal.style.top = (viewportHeight - modalHeight) / 3 + "px";
        }
        if (iframed) {
          iframe.style.maxHeight = (viewportHeight - 80) + "px";
          overlay.style.maxHeight = (viewportHeight - 88) + "px";
        } else {
          document.body.style.maxHeight = "calc(100vh - 14px)";
          overlay.style.maxHeight = "100vh";
        }
      } else {
        document.body.style.maxHeight = null;
        if (iframed) {
          iframe.style.maxHeight = null;
          parentDoc.body.classList.remove("modal");
        }
      }
    });
    document.addEventListener("DOMContentLoaded", toggleModalStyles);
  };
  toggleModalStyles();
})();

async function createOverlay() {
  const layer = document.createElement("div");
  layer.id = "overlay";
  document.body.appendChild(layer);
  return layer;
}