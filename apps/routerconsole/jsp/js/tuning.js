/**
 * @module tuning
 * @description Auto-Tuning page: checkbox toggle, section expand/collapse
 * with localStorage persistence, and AJAX refresh for live values.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {refreshElements} from "/js/refreshElements.js";

/** @constant {string} STORAGE_KEY localStorage key for expanded section state */
var STORAGE_KEY = "i2p+tuning-sections";

/**
 * Auto-tuning checkbox toggle: when unchecked, replace Min/Max/Step
 * inputs with mdash. Stores original innerHTML in data attributes
 * so inputs can be restored when re-checked.
 * @function initTuningToggles
 * @returns {void}
 */
function initTuningToggles() {
  var rows = document.querySelectorAll('#tuningtable tr[data-prefix]');
  rows.forEach(function(row) {
    var cb = row.querySelector('.tuning-toggle');
    if (!cb) return;
    var minCell = row.querySelector('td.min');
    var maxCell = row.querySelector('td.max');
    var stepCell = row.querySelector('td.step');
    if (!minCell || !maxCell || !stepCell) return;

    row.dataset.minHtml = minCell.innerHTML;
    row.dataset.maxHtml = maxCell.innerHTML;
    row.dataset.stepHtml = stepCell.innerHTML;

    function applyState() {
      if (cb.checked) {
        minCell.innerHTML = row.dataset.minHtml;
        maxCell.innerHTML = row.dataset.maxHtml;
        stepCell.innerHTML = row.dataset.stepHtml;
      } else {
        minCell.innerHTML = '&mdash;';
        maxCell.innerHTML = '&mdash;';
        stepCell.innerHTML = '&mdash;';
      }
    }

    cb.addEventListener('change', applyState);
    applyState();
  });
}

/**
 * Persist the list of expanded section headings to localStorage.
 * @function saveTuningState
 * @returns {void}
 */
function saveTuningState() {
  var expanded = [];
  document.querySelectorAll("thead.section").forEach(function(h) {
    if (h.classList.contains("expanded"))
      expanded.push(h.textContent.trim());
  });
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(expanded)); } catch(e) {}
}

/**
 * Restore expanded section headings from localStorage.
 * Matches saved labels against thead.section text content,
 * then shows the adjacent tbody and marks the heading as expanded.
 * @function restoreTuningState
 * @returns {void}
 */
function restoreTuningState() {
  try {
    var saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
    if (!saved || !saved.length) return;
    document.querySelectorAll("thead.section").forEach(function(h) {
      if (saved.indexOf(h.textContent.trim()) < 0) return;
      var el = h.nextElementSibling;
      if (el && el.tagName === "TBODY") {
        el.style.display = "table-row-group";
        h.classList.add("expanded");
      }
    });
  } catch(e) {}
}

/**
 * Initialise on DOM ready: start AJAX refresh, wire up checkbox
 * toggles, section expand/collapse via setupToggles(), restore
 * saved state, and attach click listeners to persist future changes.
 * @function initTuning
 * @returns {void}
 */
document.addEventListener("DOMContentLoaded", function() {
  refreshElements(["td.value", "td.history", "#health"], "/tuning", 5000);
  initTuningToggles();
  setupToggles("thead.section", "thead.section + tbody", "table-row-group", true, false);
  restoreTuningState();
  document.querySelectorAll("thead.section").forEach(function(h) {
    h.addEventListener("click", function() { setTimeout(saveTuningState, 0); });
  });
});
