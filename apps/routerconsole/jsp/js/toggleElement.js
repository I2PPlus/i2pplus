/* I2P+ toggleElement.js by dr|z3d */
/* Toggle display of an element by clicking on a toggle element */
/* Usage: setupToggle("toggleId", "elementId", "displayStyle"); */
/* License: AGPL3 or later */

function doToggle(toggleElement, hiddenElement, displayStyle) {
  if (hiddenElement.style.display === "none") {
    hiddenElement.style.display = displayStyle;
    toggleElement.classList.add("expanded");
  } else {
    hiddenElement.style.display = "none";
    toggleElement.classList.remove("expanded");
  }
}

function setupToggle(toggleElementId, hiddenElementId, displayStyle) {
  const toggleElement = document.getElementById(toggleElementId);
  const hiddenElement = document.getElementById(hiddenElementId);
  if (!toggleElement || !hiddenElement) return;
  toggleElement.addEventListener("click", () => {
    doToggle(toggleElement, hiddenElement, displayStyle);
  });
  toggleElement.classList.add("toggle");
}