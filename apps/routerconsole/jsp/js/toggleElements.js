/* I2P+ toggleElements.js by dr|z3d */
/* Toggle display of selectors by clicking on a specified toggle selector */
/* License: AGPL3 or later */

function setupToggles(toggleSelector, hiddenSelector, displayStyle, collapseByDefault = true, displaySingle = true) {
  const toggleElements = document.querySelectorAll(toggleSelector);
  const hiddenElements = document.querySelectorAll(hiddenSelector);

  hiddenElements.forEach(function(hiddenElement) {
    if (!hiddenElement.classList.contains("config")) {
      if (collapseByDefault) {
        hiddenElement.style.display = "none";
      } else {
        hiddenElement.style.display = displayStyle;
      }
    }
  });

  toggleElements.forEach(function(toggleElement) {
    var hiddenElement = toggleElement.nextElementSibling;

    while (hiddenElement && hiddenElement.nodeType !== Node.ELEMENT_NODE) {
      hiddenElement = hiddenElement.nextElementSibling;
    }

    if (hiddenElement) {
      toggleElement.addEventListener("click", function() {
        if (displaySingle) {
          const configElements = document.querySelectorAll(toggleElement.classList[0]);

          configElements.forEach(function(otherToggleElement) {
            if (otherToggleElement !== toggleElement && otherToggleElement.classList.contains("expanded")) {
              const otherHiddenElement = otherToggleElement.nextElementSibling;
              if (otherHiddenElement.nodeType === Node.ELEMENT_NODE) {
                otherHiddenElement.style.display = "none";
                otherToggleElement.classList.remove("expanded");
              }
            }
          });
        }

        if (!toggleElement.classList.contains("expanded")) {
          hiddenElement.style.display = displayStyle;
          toggleElement.classList.add("expanded");
        } else {
          hiddenElement.style.display = "none";
          toggleElement.classList.remove("expanded");
        }
      });
      toggleElement.classList.add("toggle");
    }
  });
}