/* I2P+ netdbLookup.js by dr|z3d */
/* Remove empty query parameters from netdb lookup queries */
/* License: AGPLv3 or later */

document.addEventListener("DOMContentLoaded", () => {
  const form = document.getElementById("netdbSearch");
  if (!form) return;
  form.querySelector('input[name="nonce"]')?.remove();
  form.addEventListener("submit", event => {
    event.preventDefault();
    const filteredFormData = new FormData();
    for (const [key, value] of new FormData(event.target)) {
      const input = form.elements[key];
      if ((input.type === "text" || input.tagName.toLowerCase() === "select" || input.type !== "hidden") && value) {
        filteredFormData.append(key, value);
      }
    }
    const url = new URL(form.action, window.location.href);
    url.search = new URLSearchParams(filteredFormData).toString();
    window.location.href = url.toString();
  });
});