// autoresize textarea based on content
// https://stackoverflow.com/a/25621277

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