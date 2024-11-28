document.addEventListener("DOMContentLoaded", () => {
  [...document.querySelectorAll(".thumb")].forEach((thumb, i) => {
    const span = document.createElement("span");
    span.classList.add("dimensions");
    span.textContent = `${thumb.naturalWidth}x${thumb.naturalHeight}`;
    const fileNameTd = document.querySelectorAll(".snarkFileName")[i];
    const link = fileNameTd.querySelector("a");
    fileNameTd.insertBefore(span, link.nextSibling);
  });
});