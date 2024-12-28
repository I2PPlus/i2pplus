/* I2P+ deleteMail.js for Susimail by dr|z3d */
/* Ensures delete mail modal is visible on instantiation */

(function handleModal() {
  let overlay = document.getElementById("overlay");

  async function toggleModalStyles() {
    if (!overlay) { overlay = await createOverlay(); }

    const modal = document.getElementById("nukemail");
    const parentDoc = window.parent.document;
    const iframe = parentDoc.getElementById("susimailframe");
    const iframed = document.documentElement.classList.contains("iframed") || window.top != window.parent.top;

    if (modal) {
      const modalRect = modal.getBoundingClientRect();
      const modalHeight = modalRect.height;
      const viewportHeight = window.parent.innerHeight;
      document.body.classList.add("modal");
      parentDoc.body.classList.add("modal");
      modal.style.top = (viewportHeight - modalHeight) / 3 + "px";
      window.scrollTo(0,0);
      if (iframed) {
        iframe.style.maxHeight = (viewportHeight - 80) + "px";
        overlay.style.maxHeight = (viewportHeight - 88) + "px";
        window.parent.scrollTo(0, 0);
      } else {overlay.style.maxHeight = "100vh";}
    } else {
      document.body.classList.remove("modal");
      document.body.style.overflow = null;
      overlay.style.zIndex = "-1";
      if (iframed) {
        iframe.style.maxHeight = null;
        overlay.style.maxHeight = null;
      }
    }

    const observer = new MutationObserver(async () => {
      const modal = document.getElementById("nukemail");
      if (!overlay) { overlay = await createOverlay(); }
      if (!modal) {
        document.body.style.overflow = null;
        if (iframed) {iframe.style.maxHeight = null;}
        document.body.classList.remove("modal");
        if (iframed) {parentDoc.body.classList.remove("modal");}
      }
    });

    observer.observe(document.body, { childList: true, subtree: true, attributes: true });
    document.addEventListener("DOMContentLoaded", toggleModalStyles);
  };

  toggleModalStyles();
})();

async function createOverlay() {
  const layer = document.createElement("div");
  layer.id = "overlay";
  layer.style.opacity = "0";
  document.body.appendChild(layer);
  return layer;
}