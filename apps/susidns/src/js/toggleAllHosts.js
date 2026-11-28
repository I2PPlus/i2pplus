/**
 * @module toggleAllHosts
 * @file I2P+ SusiDNS select-all box for the address book.
 * Writes a master checkbox into the delete column's header cell, so every host
 * the address book lists can be marked for deletion at once. The book emits
 * that cell, and this script, on every valid book view - the views that show
 * the per-row delete boxes - and on no others; see the deleteHosts cell and the
 * book.validBook gate in addressbook.jsp.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function toggleAllHosts() {

  /** The per-row boxes that mark a host for deletion, one per listed host. */
  const ROW_BOXES = "td.checkbox input[type=checkbox]";

  /**
   * Puts the master box in step with the rows: checked when every host is
   * marked, indeterminate when only some are, clear when none are.
   *
   * @returns {void}
   */
  function syncMaster() {
    const boxes = [...document.querySelectorAll(ROW_BOXES)];
    const marked = boxes.filter((box) => box.checked).length;
    const master = document.getElementById("toggleAllHosts");
    if (master) {
      master.checked = marked > 0 && marked === boxes.length;
      master.indeterminate = marked > 0 && marked < boxes.length;
    }
  }

  /**
   * Marks or clears every host checkbox and leaves the master box checked,
   * never indeterminate, whatever the state was before.
   *
   * No change event is dispatched from here. Nothing else on the page listens
   * for one on these boxes, and dispatching one per row re-ran the master-box
   * recount once per row - a pass per host, for a box this function already set.
   *
   * @param {boolean} selectAll true to mark every host, false to clear them
   * @returns {void}
   */
  function toggleAll(selectAll) {
    for (const box of document.querySelectorAll(ROW_BOXES)) {
      box.checked = selectAll;
    }
    const master = document.getElementById("toggleAllHosts");
    if (master) {
      master.checked = selectAll;
      master.indeterminate = false;
    }
  }

  /**
   * Writes the master box into the delete column's header cell and wires it to
   * the rows. A page without that cell is not a valid book, so it has no
   * per-row delete boxes either, and is left alone quietly.
   *
   * @listens DOMContentLoaded
   * @returns {void}
   */
  function createSelectAllCheckbox() {
    // The id is what marks the cell out, and ids are unique, so it needs no
    // table or class qualifiers to find it.
    const cell = document.getElementById("deleteHosts");
    if (!cell) { return; }

    const master = document.createElement("input");
    master.type = "checkbox";
    master.id = "toggleAllHosts";
    cell.appendChild(master);

    master.addEventListener("change", () => toggleAll(master.checked));

    document.addEventListener("change", (e) => {
      const box = e.target;
      if (box instanceof HTMLInputElement && box.type === "checkbox" && box.closest("td.checkbox")) {
        syncMaster();
      }
    });
  }

  document.addEventListener("DOMContentLoaded", createSelectAllCheckbox);
}());
