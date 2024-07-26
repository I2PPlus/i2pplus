/* I2P+ Section Toggler by dr|z3d */
/* Provides a toggler for table sections displaying none at startup,
/* otherwise a single section at a time */
/* License: AGPL3 or later */

function toggleRows() {
  const sectionRows = document.querySelectorAll(".section");
  let activeSection = null;

  sectionRows.forEach(row => {
    // Hide everything except headings by default
    let nextRow = row.nextElementSibling;
    while (nextRow && !nextRow.classList.contains("section")) {
      nextRow.style.display = "none";
      nextRow = nextRow.nextElementSibling;
    }

    row.addEventListener("click", () => {
      // Check if the clicked section is the same as the active section
      const isSameSection = activeSection === row;

      // Hide rows of the currently active section, if any
      if (activeSection && !isSameSection) {
        let activeNextRow = activeSection.nextElementSibling;
        while (activeNextRow && !activeNextRow.classList.contains("section")) {
          activeNextRow.style.display = "none";
          activeNextRow = activeNextRow.nextElementSibling;
        }
        activeSection.classList.remove("active");
      }

      // Show or hide rows depending on whether it's the same section or not
      nextRow = row.nextElementSibling;
      while (nextRow && !nextRow.classList.contains("section")) {
        nextRow.style.display = isSameSection ? "none" : "";
        nextRow = nextRow.nextElementSibling;
      }

      // Toggle the active class depending on the visibility of the rows
      const hasVisibleRows = row.nextElementSibling && row.nextElementSibling.style.display !== "none";
      row.classList.toggle("active", hasVisibleRows);

      // Update the active section
      activeSection = isSameSection ? null : row;
    });
  });
}

document.addEventListener("DOMContentLoaded", toggleRows);
