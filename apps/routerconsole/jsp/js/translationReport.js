/**
 * @module translationReport
 * @description Toggles visibility of completed translation rows in compiled
 * and file-based translation report tables. Allows users to show/hide
 * completed translations for easier scanning of incomplete work.
 * @author dr|z3d
 * @license AGPL3 or later
 */

const toggleButtonCompiled = document.getElementById("tx_toggle_compiled");
const toggleButtonFiles = document.getElementById("tx_toggle_files");
const tx_summary = document.getElementById("tx_summary");

/**
 * Hides completed translation rows and marks tables with no incomplete rows as hidden.
 * @function hideComplete
 * @param {NodeList} txTables - The translation table elements to process
 * @returns {void}
 */
function hideComplete(txTables) {
  const showCompleteClass = tx_summary.classList.contains("showAll_compiled") ? "showAll_compiled" : "showAll_files";
  tx_summary.classList.remove(showCompleteClass);
  toggleButtonCompiled.textContent = "Show complete translations";
  toggleButtonFiles.textContent = "Show complete translations";
  txTables.forEach(function(table) {
    const tbodyElements = table.querySelectorAll("tbody");
    const incompleteRows = table.querySelectorAll(".incomplete");
    const completeRows = table.querySelectorAll(".complete");
    if (incompleteRows.length === 0) {
      table.classList.add("hidden");
    }
  });
}

/**
 * Shows all translation rows and applies the showAll CSS class to the summary.
 * @function showComplete
 * @param {NodeList} txTables - The translation table elements to show
 * @param {string} showCompleteClass - The CSS class to toggle on the summary container
 * @returns {void}
 */
function showComplete(txTables, showCompleteClass) {
  toggleButtonCompiled.textContent = "Hide complete translations";
  toggleButtonFiles.textContent = "Hide complete translations";
  txTables.forEach(function(table) {
    const tbodyElements = table.querySelectorAll("tbody");
    table.classList.remove("hidden");
  });
  tx_summary.classList.add(showCompleteClass);
}

toggleButtonCompiled.addEventListener("click", function() {
  const txTables = tx_summary.querySelectorAll(".tx_compiled");
  const showCompleteClass = "showAll_compiled";
  if (tx_summary.classList.contains(showCompleteClass)) {
    hideComplete(txTables);
  } else {
    showComplete(txTables, showCompleteClass);
  }
});

toggleButtonFiles.addEventListener("click", function() {
  const txTables = tx_summary.querySelectorAll(".tx_file");
  const showCompleteClass = "showAll_files";
  if (tx_summary.classList.contains(showCompleteClass)) {
    hideComplete(txTables);
  } else {
    showComplete(txTables, showCompleteClass);
  }
});

tx_summary.addEventListener("classlistchange", function(event) {
  if (event.target.classList.contains("showAll_compiled")) {
    toggleButtonCompiled.textContent = event.target.classList.contains("showAll_compiled") ? "Hide complete translations" : "Show complete translations";
  }
  if (event.target.classList.contains("showAll_files")) {
    toggleButtonFiles.textContent = event.target.classList.contains("showAll_files") ? "Hide complete translations" : "Show complete translations";
  }
});

document.addEventListener("DOMContentLoaded", function() {
  const txTablesCompiled = tx_summary.querySelectorAll(".tx_compiled");
  const txTablesFiles = tx_summary.querySelectorAll(".tx_file");
  hideComplete(txTablesCompiled);
  hideComplete(txTablesFiles);
});