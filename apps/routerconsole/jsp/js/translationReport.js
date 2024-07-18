/* I2P+ translationReport.js by dr|z3d */
/* Toggle completed translation rows in compiled translation tables */
/* License: AGPL3 or later */

const toggleButtonCompiled = document.getElementById("tx_toggle_compiled");
const toggleButtonFiles = document.getElementById("tx_toggle_files");
const tx_summary = document.getElementById("tx_summary");

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