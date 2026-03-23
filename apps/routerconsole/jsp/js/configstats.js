/**
 * @module configstats
 * @description Sorts stat groups using natural sort order and displays dynamic
 * counts of checked/total configured stats on the /configstats page.
 * Tracks checkbox changes to indicate unsaved state.
 * @author dr|z3d
 * @license AGPL3 or later
 */

document.addEventListener("DOMContentLoaded", function () {

  /**
   * Updates or creates a count span in the table header showing checked/total stats.
   * @function updateCountSpan
   * @param {HTMLTableHeaderCellElement} thElement - The table header element to update
   * @param {number} checkedItems - Number of checked checkboxes
   * @param {number} totalItems - Total number of checkboxes
   * @param {boolean} needsSaving - Whether the state differs from initial state
   * @returns {void}
   */
  function updateCountSpan(thElement, checkedItems, totalItems, needsSaving) {
    let countSpan = thElement.querySelector("span") || document.createElement("span");
    countSpan.classList.add("statcount");
    countSpan.textContent = (needsSaving ? "* " : "") + `${checkedItems} / ${totalItems}`;
    thElement.appendChild(countSpan);
  }

  /**
   * Captures the current checked state of all checkboxes as an object.
   * @function getCheckboxState
   * @param {NodeList} checkboxes - The checkbox elements to capture state from
   * @returns {Object<string, boolean>} Map of checkbox value to checked state
   */
  function getCheckboxState(checkboxes) {
    return Array.from(checkboxes).reduce((acc, cb) => ({ ...acc, [cb.value]: cb.checked }), {});
  }

  /**
   * Performs a natural (human-friendly) sort comparison between two strings.
   * @function naturalSort
   * @param {string} a - First string to compare
   * @param {string} b - Second string to compare
   * @returns {number} Negative if a < b, positive if a > b, zero if equal
   */
  function naturalSort(a, b) {
    const re = /(\d+)|(\D+)/g;
    const aParts = a.match(re);
    const bParts = b.match(re);

    for (let i = 0; i < Math.min(aParts.length, bParts.length); i++) {
      const aPart = aParts[i];
      const bPart = bParts[i];

      if (aPart !== bPart) {
        const aIsNumber = !isNaN(aPart);
        const bIsNumber = !isNaN(bPart);

        if (aIsNumber && bIsNumber) {
          return parseInt(aPart, 10) - parseInt(bPart, 10);
        }
        if (aIsNumber) {
          return -1;
        }
        if (bIsNumber) {
          return 1;
        }
        return aPart.localeCompare(bPart);
      }
    }
    return aParts.length - bParts.length;
  }

  /**
   * Initializes stat group rows with checkbox listeners and count displays.
   * @function initialize
   * @returns {Object<string, Object<string, boolean>>} Map of section IDs to their initial checkbox states
   */
  function initialize() {
    const graphableStatRows = document.querySelectorAll(".graphableStat");
    const initialState = {};

    graphableStatRows.forEach(row => {
      const td = row.querySelector("td");
      const checkboxes = td.querySelectorAll("input[type=checkbox]");
      const totalItems = checkboxes.length;
      const checkedItems = Array.from(checkboxes).filter(cb => cb.checked).length;

      const thElement = row.previousElementSibling.querySelector("th");
      if (thElement) {updateCountSpan(thElement, checkedItems, totalItems, false);}
      const sectionId = thElement.textContent.trim();
      initialState[sectionId] = getCheckboxState(checkboxes);

      checkboxes.forEach(cb => {
        cb.addEventListener("change", () => {
          const currentSectionState = getCheckboxState(checkboxes);
          const needsSaving = JSON.stringify(currentSectionState) !== JSON.stringify(initialState[sectionId]);
          updateCountSpan(thElement, Array.from(checkboxes).filter(cb => cb.checked).length, totalItems, needsSaving);
        });
      });
    });

    return initialState;
  }

  /**
   * Renders the sorted stat sections back into the table body.
   * @function renderSortedSections
   * @param {Array<{header: HTMLElement, content: HTMLElement[]}>} sections - Sorted sections to render
   * @returns {void}
   */
  function renderSortedSections(sections) {
    const tableBody = document.querySelector("#configstats tbody");
    tableBody.innerHTML = "";
    const fragment = document.createDocumentFragment();

    sections.forEach(section => {
      fragment.appendChild(section.header);
      section.content.forEach(content => fragment.appendChild(content));
    });

    tableBody.appendChild(fragment);
  }

  const initialState = initialize(), sections = [];
  let currentSection = null;

  document.querySelectorAll(".statgroup, .graphableStat").forEach(element => {
    if (element.classList.contains("statgroup")) {
      if (currentSection) {sections.push(currentSection);}
      currentSection = { header: element, content: [] };
    } else {currentSection.content.push(element);}
  });

  if (currentSection) { sections.push(currentSection); }
  sections.sort((a, b) => naturalSort(a.header.querySelector("th").textContent.trim(), b.header.querySelector("th").textContent.trim()));
  renderSortedSections(sections);

  document.querySelectorAll("tr.statgroup th b").forEach(b => {
    b.textContent = b.textContent.replace(/([a-z])([A-Z])/g, "$1 $2");
    b.textContent = b.textContent.replace("Co Del", "CoDel");
    b.innerHTML = b.innerHTML.replace(/\[([^\]]+)\]/g, "<span class=subcat>$1</span>");
  });

  document.querySelector("#configstats .optionsave>.cancel").addEventListener("click", (event) => {
    event.preventDefault();
    location.reload();
  });

});