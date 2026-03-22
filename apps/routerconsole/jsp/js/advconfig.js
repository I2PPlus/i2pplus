/**
 * @module advconfig
 * @description Enhances the /configadvanced page in advanced mode by converting
 * the raw textarea configuration into an editable table with inline key-value editing,
 * delete buttons, filtering, and form submission handling.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Initializes the advanced configuration interface by converting the textarea
 * into an editable table with key-value pairs, filter, and CRUD operations.
 * @function advConfigInit
 * @returns {void}
 */
const advConfigInit = () => {
  const d = document;

  /**
   * Extends the Element prototype with shorthand query methods.
   * @param {string} selector - CSS selector string
   * @returns {Element|NodeList} Selected element(s)
   */
  Element.prototype.query = function(selector) { return this.querySelector(selector); };
  Element.prototype.queryAll = function(selector) { return this.querySelectorAll(selector); };
  Element.prototype.hasClass = function(className) { return this.classList.contains(className); };

  const query = selector => d.querySelector(selector);
  const queryAll = selector => d.querySelectorAll(selector);

  const selectors = {
    container: query("td.tabletextarea"),
    textarea: query("textarea#advancedsettings"),
    table: query("#advconf"),
    infohelp: query("#advconf thead td.infohelp"),
    header: query("h3#advancedconfig"),
    advForm: query("#advConfigForm"),
    saveButton: query("#saveConfig input.accept"),
    cancelButton: query("#saveConfig input.cancel")
  };

  const { container, textarea, table, infohelp, header, advForm, saveButton, cancelButton } = selectors;

  d.body.classList.add("js");
  textarea.style.display = "none";
  infohelp.setAttribute("colspan", "3");

  /**
   * Removes old configuration rows from the DOM before rebuilding the table.
   * @function clearOldRows
   * @returns {void}
   */
  const clearOldRows = () => {
    while (container.parentNode.firstChild && container.parentNode.firstChild.firstChild !== textarea) {
      container.parentNode.removeChild(container.parentNode.firstChild);
    }
  };
  clearOldRows();

  const configItems = textarea.value.split("\n")
    .map(item => item.split("="))
    .filter(parts => parts.length === 2)
    .sort((a, b) => a[0].localeCompare(b[0]));

  /**
   * Creates a table row with editable key and value cells and a delete cell.
   * @function createRow
   * @param {string} key - The configuration key name
   * @param {string} value - The configuration value
   * @returns {HTMLTableRowElement} The created table row element
   * @example createRow("i2np.bandwidth.inboundKByteSec", "1024")
   */
  const createRow = (key, value) => {
    const row = d.createElement("tr");
    row.className = "configline";

    const keyCell = d.createElement("td");
    const valueCell = d.createElement("td");
    const delCell = d.createElement("td");

    keyCell.textContent = key;
    valueCell.textContent = value;
    keyCell.setAttribute("contenteditable", "true");
    valueCell.setAttribute("contenteditable", "true");

    [keyCell, valueCell, delCell].forEach(cell => cell.setAttribute("spellcheck", "false"));
    [keyCell.className, valueCell.className, delCell.className] = ["key", "value", "delete"];

    row.append(keyCell, valueCell, delCell);
    return row;
  };

  let lastConfigRow = null;
  configItems.forEach(([key, value]) => {
    const row = createRow(key, value);
    lastConfigRow = table.query("#advconf tbody tr:last-child");
    lastConfigRow.insertAdjacentElement("afterend", row);
    lastConfigRow = row;
  });

  const addNewRow = d.createElement("tr");
  addNewRow.id = "addNew";
  const newKeyCell = d.createElement("td");
  const newValueCell = d.createElement("td");
  const newDelCell = d.createElement("td");

  addNewRow.append(newKeyCell, newValueCell, newDelCell);
  [newKeyCell, newValueCell].forEach(cell => {
    cell.contentEditable = true;
    cell.setAttribute("spellcheck", "false");
  });
  newKeyCell.className = "newKey";
  newValueCell.className = "newValue";
  newDelCell.className = "delete";

  const firstConfigRow = table.query(".configline");
  if (firstConfigRow) { firstConfigRow.insertAdjacentElement("beforebegin", addNewRow); }

  const observer = new MutationObserver(mutationsList => {
    mutationsList.forEach(mutation => {
      mutation.addedNodes.forEach(node => {
        if (node.tagName === "TD") { updateTextarea(); }
      });
    });
  });

  observer.observe(table, { childList: true, subtree: true });

  /**
   * Synchronizes the hidden textarea with the current state of the editable table rows.
   * @function updateTextarea
   * @returns {void}
   */
  const updateTextarea = () => {
    const updatedItems = [];
    const rows = table.queryAll(".configline");
    rows.forEach(row => {
      const key = row.query(".key").textContent.trim();
      const value = row.query(".value").textContent.trim();
      if (key) { updatedItems.push(`${key}=${value}`); }
    });
    textarea.value = updatedItems.join("\n");
  };

  table.addEventListener("click", event => {
    const target = event.target;
    if (target.hasClass("delete")) {
      const row = target.closest("tr");
      if (row.id !== "addNew") {
        const removedKey = row.query("td:first-child").textContent;
        row.remove();
        updateTextarea();
        table.query("thead").removeAttribute("hidden");
        infohelp.id = "removeKey";
        infohelp.innerHTML = msgKeyRemove.replace("{0}", removedKey);
        infohelp.scrollIntoView();
      } else {
        newKeyCell.textContent = "";
        newValueCell.textContent = "";
      }
    }
  });

  /**
   * Submits a new key-value pair by creating a row and clearing the input fields.
   * @function submitNewKeyValue
   * @returns {void}
   */
  const submitNewKeyValue = () => {
    const newKey = newKeyCell.textContent.trim();
    const existingKey = configItems.some(item => item[0] === newKey);
    const newValue = newValueCell.textContent.trim();
    if (newKey) {
      const newRow = createRow(newKey, newValue);
      addNewRow.insertAdjacentElement("beforebegin", newRow);
      newKeyCell.textContent = "";
      newValueCell.textContent = "";
      updateTextarea();
    }
  };

  /**
   * Adds a filter input to the configuration header for searching key-value pairs.
   * @function addFilter
   * @returns {void}
   */
  const addFilter = () => {
    let filterValue = "";
    const advFilter = d.createElement("span");
    advFilter.id = "advfilter";

    const filterInput = d.createElement("input");
    filterInput.type = "text";
    filterInput.id = "filterInput";
    advFilter.appendChild(filterInput);

    filterInput.addEventListener("input", event => {
      filterValue = event.target.value.toLowerCase();
      const rows = table.queryAll(".configline");
      rows.forEach(row => {
        const key = row.query(".key").textContent.toLowerCase();
        const value = row.query(".value").textContent.toLowerCase();
        row.style.display = (key.includes(filterValue) || value.includes(filterValue)) ? "table-row" : "none";
      });
    });

    const clearFilter = d.createElement("button");
    clearFilter.textContent = "X";
    clearFilter.addEventListener("click", () => {
      const activeFilter = document.getElementById("filterInput");
      activeFilter.value = "";
      const event = new Event('input', { bubbles: true });
      activeFilter.dispatchEvent(event);
      window.scrollTo(0,0);
    });

    advFilter.appendChild(clearFilter);
    header.appendChild(advFilter);
  };
  addFilter();

  /**
   * Handles save logic: validates new key-value inputs, checks for duplicates,
   * and triggers form submission.
   * @function doSave
   * @param {Event} event - The keyboard or click event
   * @returns {void}
   */
  const doSave = event => {
    const modalActive = query("#modalActive");
    if (modalActive) {return;}
    updateTextarea();
    const newKey = newKeyCell.textContent.trim();
    const newValue = newValueCell.textContent.trim();

    if (newValue && !newKey) {
      modal("No keyname provided for the submitted value.<br>Please supply a keyname.");
      newKeyCell.focus();
      return;
    }

    if (newKey) {
      const existingKey = configItems.some(item => item[0] === newKey);
      if (existingKey) {
        modal(`Duplicate key <b>${newKey}</b> submitted. Please modify the existing key.`);
        newKeyCell.textContent = "";
        newValueCell.textContent = "";
        const filterInput = query("#advfilter input");
        filterInput.value = newKey;
        filterInput.dispatchEvent(new Event("input", { bubbles: true }));
        return;
      }
      submitNewKeyValue();
    }

    if (event.key === "Enter" || event.type === "click") {
      saveButton.click();
    }
  };

  d.addEventListener("keydown", event => {
    if (event.key === "Enter") {
      event.preventDefault();
      doSave(event);
    } else if (event.key === "Escape") {
      event.preventDefault();
      resetForm(event);
    }
  });

  /**
   * Resets the form to the original configuration stored in a hidden input.
   * @function resetForm
   * @param {Event} event - The event that triggered the reset
   * @returns {void}
   */
  const resetForm = event => {
    event.preventDefault();
    textarea.value = query("input[name=nofilter_oldConfig]").value;
    advForm.requestSubmit(saveButton);
  };

  cancelButton.addEventListener("click", resetForm);
  saveButton.addEventListener("click", event => {
    doSave(event);
  });

  let statusAdded = false;
  /**
   * Polls for the floodfill config element and adds a status badge to the header.
   * @function floodfillStatus
   * @returns {void}
   */
  function floodfillStatus() {
    const h3ff = document.querySelector("#ffconf");
    if (h3ff) {
      const info = document.createElement("span");
      info.id = "ffstatus";
      const ffstatus = document.querySelector("#floodfillconfig .infohelp").textContent.match(/\(.*?\)/);
      if (ffstatus && ffstatus.length > 0) {
        info.textContent = ffstatus[0].replace(/[\(\).]/g, "");
        if (!statusAdded) {
          h3ff.appendChild(info);
          statusAdded = true;
        }
      }
    } else {setTimeout(floodfillStatus, 100);}
  };
  floodfillStatus();
}

document.addEventListener("DOMContentLoaded", advConfigInit);