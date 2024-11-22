/* I2P+ configstats.js by dr|z3d */
/* Sort and display dynamic count of checked / total configured stats on /configstats */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {
  if (theme !== "dark") return;

  function updateCountSpan(thElement, checkedItems, totalItems, needsSaving) {
    let countSpan = thElement.querySelector("span") || document.createElement("span");
    countSpan.classList.add("statcount");
    countSpan.textContent = (needsSaving ? '* ' : '') + `${checkedItems} / ${totalItems}`;
    thElement.appendChild(countSpan);
  }

  function getCheckboxState(checkboxes) {
    return Array.from(checkboxes).reduce((acc, cb) => ({ ...acc, [cb.value]: cb.checked }), {});
  }

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
        } else if (aIsNumber) {
          return -1;
        } else if (bIsNumber) {
          return 1;
        } else {
          return aPart.localeCompare(bPart);
        }
      }
    }
    return aParts.length - bParts.length;
  }

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

  if (currentSection) sections.push(currentSection);
  sections.sort((a, b) => naturalSort(a.header.querySelector("th").textContent.trim(), b.header.querySelector("th").textContent.trim()));
  renderSortedSections(sections);

  document.querySelector("#configstats .optionsave>.cancel").addEventListener("click", (event) => {
    event.preventDefault();
    location.reload();
  });

});