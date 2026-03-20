/**
 * @module toggleElements
 * @description Generic toggle system for showing/hiding DOM elements via CSS selectors.
 * Supports single-section-at-a-time display mode, expand/collapse state classes,
 * and scroll-into-view on expand.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Sets up toggle behavior for elements matching the given selectors.
 * @function setupToggles
 * @param {string} toggleSelector - CSS selector for toggle trigger elements
 * @param {string} hiddenSelector - CSS selector for elements that should be toggled
 * @param {string} displayStyle - CSS display value when expanded (e.g., "block", "table-row-group")
 * @param {boolean} [collapseByDefault=true] - Whether to hide elements initially
 * @param {boolean} [displaySingle=true] - Whether only one section can be open at a time
 * @returns {void}
 */
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
          toggleElement.scrollIntoView({block: "center", inline: "center"});
        } else {
          hiddenElement.style.display = "none";
          toggleElement.classList.remove("expanded");
          document.documentElement.classList.add("hasCollapsedElement");
        }
        if ([...toggleElements].some(element => element.classList.contains('expanded'))) {document.body.classList.add("hasExpandedElement");}
        else {document.body.classList.remove("hasExpandedElement");}
      });
      toggleElement.classList.add("toggle");
    }
  });
}