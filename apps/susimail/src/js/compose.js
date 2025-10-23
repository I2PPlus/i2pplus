// When the user attempts to leave the page, show a confirmation prompt
let allowUnload = false;

window.addEventListener("beforeunload", (event) => {
  if (!allowUnload) {
    event.returnValue = ""; // Standard way to trigger the prompt in most browsers
  }
});

// Initialize the popup-related behavior
function initBeforeUnloadGuard() {
  const triggerButtons = document.getElementsByClassName("confirm");
  // Use forEach-like pattern with Array.from for readability
  Array.from(triggerButtons).forEach((button) => {
    attachGuardButtonHandler(button);
  });
}

// Attach a click handler that disables the unload prompt
function attachGuardButtonHandler(element) {
  element.addEventListener("click", () => {
    allowUnload = true;
  });
}

document.addEventListener("DOMContentLoaded", () => {
  initBeforeUnloadGuard();
}, { once: true });
