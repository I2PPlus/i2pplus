/**
 * @module toggleAllHosts
 * @file I2P+ SusiDNS toggle-all-hosts utility.
 * Provides a master checkbox to select/deselect all hosts for deletion
 * when the "dead hosts" filter is active.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Toggles every host checkbox to the given state and updates the
 * master "select all" checkbox accordingly.
 * @function toggleAll
 * @param {boolean} selectAll - True to check all boxes, false to uncheck.
 * @returns {void}
 * @example
 * toggleAll(true);  // select all hosts
 * toggleAll(false); // deselect all hosts
 */
function toggleAll(selectAll) {
    const deadFilterActive = document.getElementById("deadHosts");
    if (!deadFilterActive) { return; }

    const targetCheckboxes = [
        ...document.querySelectorAll("td.checkbox input[type='checkbox']")
    ];

    targetCheckboxes.forEach(checkbox => {
        checkbox.checked = selectAll;
        const event = new Event("change", { bubbles: true });
        checkbox.dispatchEvent(event);
    });

    const toggleAllCheckbox = document.getElementById("toggleAllHosts");
    if (toggleAllCheckbox) {
        toggleAllCheckbox.checked = selectAll;
        toggleAllCheckbox.indeterminate = false;
    }
}

/**
 * Creates and injects a "select all" checkbox into the dead-hosts header cell.
 * Sets up change listeners to keep the master checkbox in sync with
 * individual host checkboxes.
 * @function createSelectAllCheckbox
 * @returns {void}
 */
function createSelectAllCheckbox() {
    if (document.getElementById("toggleAllHosts")) { return; }

    const targetCell = document.querySelector("#host_list th.checkbox#deadHosts");
    if (!targetCell) {
        console.warn("Could not find target cell: #host_list th.checkbox#deadHosts");
        return;
    }

    targetCell.innerHTML = "";

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.id = "toggleAllHosts";

    targetCell.appendChild(checkbox);

    checkbox.addEventListener("change", () => {
        toggleAll(checkbox.checked);
    });

    document.addEventListener("change", e => {
        const target = e.target;
        if (!(target instanceof HTMLInputElement) || target.type !== "checkbox") { return; }

        const isMarkedForDeletion = target.name && target.name.startsWith("markedForDeletion");
        const isTableCellCheckbox = target.closest("td.checkbox");

        if (!isMarkedForDeletion && !isTableCellCheckbox) { return; }

        const checkboxes = [
            ...document.querySelectorAll("input[type='checkbox'][name^='markedForDeletion']"),
            ...document.querySelectorAll("td.checkbox input[type='checkbox']")
        ];

        const checkedCount = checkboxes.filter(cb => cb.checked).length;

        const masterCheckbox = document.getElementById("toggleAllHosts");
        if (masterCheckbox) {
            masterCheckbox.checked = checkedCount > 0;
            masterCheckbox.indeterminate = checkedCount > 0 && checkedCount < checkboxes.length;
        }
    });
}

document.addEventListener("DOMContentLoaded", () => {
    createSelectAllCheckbox();
});