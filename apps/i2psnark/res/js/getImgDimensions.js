/* I2P+ getImgDimensions.js for I2PSnark by dr|z3d */
/* Display image dimensions in Snark's file viewer */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", () => {
  [...document.querySelectorAll(".thumb")].forEach(thumb => {
    const span = document.createElement("span");
    span.classList.add("dimensions");
    span.textContent = `${thumb.naturalWidth}x${thumb.naturalHeight}`;
    const fileIconTd = thumb.closest(".fileIcon");
    if (fileIconTd) {
      const parentRow = fileIconTd.closest("tr");
      const snarkFileNameTd = Array.from(parentRow.cells).find(cell => cell.classList.contains("snarkFileName"));
      if (snarkFileNameTd) {
        const link = snarkFileNameTd.querySelector("a");
        if (link) {snarkFileNameTd.insertBefore(span, link.nextSibling);}
      }
    }
  });
});