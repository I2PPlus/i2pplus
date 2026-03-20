/**
 * @module textareaResize
 * @description Auto-resizes textarea elements to fit their content height.
 * Based on https://stackoverflow.com/a/25621277
 * @license AGPL3 or later
 */

/**
 * Adds an input listener to a textarea that dynamically adjusts its height to fit content.
 * @function addResizeListener
 * @param {HTMLTextAreaElement} element - The textarea element to make auto-resizing
 * @returns {void}
 */
function addResizeListener(element) {
  if (!element || element.tagName !== "textarea") {
    return;
  }

  element.setAttribute("style", "height:" + (element.scrollHeight) + "px;overflow-y:hidden;");
  element.addEventListener("input", onInput, false);

  function onInput() {
    this.style.height = "auto";
    this.style.height = (this.scrollHeight) + "px";
  }
}