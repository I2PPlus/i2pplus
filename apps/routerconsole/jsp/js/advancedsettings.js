/* I2P+ advancedsettings.js by dr|z3d */
/* Toggles individual config help text on /help/advancedsettings */
/* License: AGPL3 or later */

(function() {
const configElements = document.querySelectorAll(".config");

  function doToggle(toggleElement, hiddenElement, displayStyle) {
    if (!toggleElement.classList.contains("expanded")) {
      configElements.forEach(function(otherToggleElement) {
        if (otherToggleElement !== toggleElement && otherToggleElement.classList.contains("expanded")) {
          const otherHiddenElement = otherToggleElement.nextElementSibling;
          if (otherHiddenElement.nodeType === Node.ELEMENT_NODE) {
            otherHiddenElement.style.display = "none";
            otherToggleElement.classList.remove("expanded");
          }
        }
      });

      hiddenElement.style.display = displayStyle;
      toggleElement.classList.add("expanded");
    } else {
      hiddenElement.style.display = "none";
      toggleElement.classList.remove("expanded");
    }
  }

  function setupToggles(hiddenSelector, displayStyle) {
    const toggleElements = document.querySelectorAll("tr.config");
    const hiddenElements = document.querySelectorAll(hiddenSelector);

    hiddenElements.forEach(function(hiddenElement) {
      if (!hiddenElement.classList.contains("config")) {
        hiddenElement.style.display = "none";
      }
    });

    toggleElements.forEach(function(toggleElement) {
      var hiddenElement = toggleElement.nextElementSibling;

      while (hiddenElement && hiddenElement.nodeType !== Node.ELEMENT_NODE) {
        hiddenElement = hiddenElement.nextElementSibling;
      }

      if (hiddenElement) {
        toggleElement.addEventListener("click", function() {
          doToggle(toggleElement, hiddenElement, displayStyle);
        });
        toggleElement.classList.add("toggle");
      }
    });
  }

  setupToggles("tr:not(.config):not(.section)", "table-row");

})();