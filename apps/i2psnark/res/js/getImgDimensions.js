/**
 * @module getImgDimensions
 * @file getImgDimensions.js - Display image dimensions in I2PSnark's file viewer.
 * @description On DOM load, iterates over all thumbnail images (.thumb) in the file listing,
 * reads their natural dimensions, and inserts a span element displaying the width x height
 * next to the file name link.
 * @author dr|z3d
 * @license AGPL3 or later
 */

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