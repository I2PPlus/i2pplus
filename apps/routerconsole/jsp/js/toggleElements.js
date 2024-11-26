/* I2P+ toggleElements.js by dr|z3d */
/* Toggle display of selectors by clicking on a specified toggle selector */
/* License: AGPL3 or later */

function setupToggles(toggleSelector, hiddenSelector, displayStyle, collapseByDefault = true, displaySingle = true) {
  const toggleElements = document.querySelectorAll(toggleSelector);
  const hiddenElements = document.querySelectorAll(hiddenSelector);

  if (toggleElements && hiddenElements) {document.body.classList.add("toggleElementsActive");}
  else {return;}

  hiddenElements.forEach(function(hiddenElement) {
    if (!hiddenElement.classList.contains("config")) {
      if (collapseByDefault) {hiddenElement.style.display = "none";}
      else {hiddenElement.style.display = displayStyle;}
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
          const relevantToggleElements = Array.from(toggleElements).filter(function(element) {
            return element.nextElementSibling === hiddenElement || element.nextElementSibling && element.nextElementSibling.classList.contains("toggle");
          });

          relevantToggleElements.forEach(function(otherToggleElement) {
            if (otherToggleElement !== toggleElement && otherToggleElement.classList.contains("expanded")) {
              const otherHiddenElement = otherToggleElement.nextElementSibling;
              if (otherHiddenElement.nodeType === Node.ELEMENT_NODE) {
                otherHiddenElement.style.display = "none";
                otherToggleElement.classList.remove("expanded");
              }
            }
          });

          const nonExpandedHiddenElements = Array.from(hiddenElements).filter(function(element) {
            return !element.classList.contains("expanded") && element !== hiddenElement;
          });

          nonExpandedHiddenElements.forEach(function(nonExpandedHiddenElement) {
            nonExpandedHiddenElement.style.display = "none";
          });

          toggleElements.forEach(function(otherToggleElement) {
            if (otherToggleElement !== toggleElement) {
              otherToggleElement.classList.remove("expanded");
            }
          });
        }

        if (!toggleElement.classList.contains("expanded")) {
          hiddenElement.style.display = displayStyle;
          toggleElement.classList.add("expanded");
          document.documentElement.classList.remove("hasCollapsedElement");
          document.documentElement.classList.add("hasExpandedElement");
          toggleElement.scrollIntoView();
        } else {
          hiddenElement.style.display = "none";
          toggleElement.classList.remove("expanded");
          document.documentElement.classList.remove("hasExpandedElement");
          document.documentElement.classList.add("hasCollapsedElement");
        }
      });
      toggleElement.classList.add("toggle");
    }
  });
}