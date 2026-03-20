/**
 * @module toggleConfigs
 * @file toggleConfigs.js - Enable toggling of I2PSnark's tracker and filter configuration panels.
 * @description Provides expand/collapse functionality for the file filter and tracker
 * configuration tables in I2PSnark. Handles iframe-aware scrolling by posting messages
 * to the parent frame when embedded, or scrolling natively in standalone mode.
 * @author dr|3d
 * @license AGPL3 or later
 */

/**
 * @type {?HTMLTableElement}
 * @description The file filter configuration table element.
 */
const filterConfigTable = document.querySelector("#fileFilter table");

/**
 * @type {?HTMLTableElement}
 * @description The tracker configuration table element.
 */
const trackerConfigTable = document.querySelector("#trackers table");

/**
 * @type {boolean}
 * @description Whether the page is running inside an iframe.
 */
const isIframed = document.documentElement.classList.contains("iframed") || window.self != window.top;

/**
 * @function toggleConfig
 * @description Toggles the visibility of a configuration table. When shown, adds the
 * "expanded" class to the title and scrolls it into view. When hidden, removes the
 * class. In iframe mode, posts messages to the parent frame for scrolling and resizing.
 * @param {HTMLTableElement} table - The configuration table to show or hide.
 * @param {HTMLElement} title - The title element that acts as the toggle header.
 * @returns {void}
 */
function toggleConfig(table, title) {
  if (table.style.display === "none" || table.style.display === "") {
    table.style.display = "table";
    title.classList.add("expanded");

    if (isIframed) {
      parent.postMessage({ command: "scrollToElement", id: title.id }, location.origin);
      parent.postMessage({ action: 'resize', iframeId: 'i2psnarkframe' }, location.origin);
    } else {scrollToElement(title);}
  } else {
    table.style.display = "none";
    title.classList.remove("expanded");
  }
}

/**
 * @function scrollToElement
 * @description Smoothly scrolls the given element into view within the specified window context.
 * Calculates the element's position relative to the viewport and scrolls to it.
 * @param {HTMLElement} element - The element to scroll into view.
 * @param {Window} [windowObj=window] - The window object to scroll (useful for parent window in iframe mode).
 * @returns {void}
 */
function scrollToElement(element, windowObj = window) {
  const elementPosition = element.getBoundingClientRect().top;
  const scrollPosition = elementPosition + (windowObj !== window ? windowObj.pageYOffset : window.pageYOffset);
  windowObj.scrollTo({ top: scrollPosition, behavior: "smooth" });
}

document.addEventListener("DOMContentLoaded", () => {
  const configTitles = document.querySelectorAll(".configTitle");
  configTitles.forEach((title) => title.classList.remove("expanded"));

  const setupClickListener = async (elementId, configTable) => {
    const button = document.getElementById(elementId);
    button.addEventListener("click", async (e) => {
      const clickedTitle = e.target.closest(".configTitle");
      if (clickedTitle) {
        await toggleConfig(configTable, clickedTitle);
        setTimeout(() => {
          requestAnimationFrame(() => {
            clickedTitle.scrollIntoView({block: "center", inline: "center", behavior: "smooth"});
          });
        }, 60);
      }
    });
  };

  setupClickListener("fileFilter", filterConfigTable);
  setupClickListener("trackers", trackerConfigTable);
});