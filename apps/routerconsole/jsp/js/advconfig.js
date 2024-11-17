/* I2P+ advconfig.js by dr|z3d */
/* Enhance /configadvanced in advanced mode */
/* License: AGPL3 or later */

const init = () => {
  const d = document;

  // Extend Element prototype
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
    advForm: query("#advancedconfig+form"),
    saveButton: query("#saveConfig input[type=submit]"),
    cancelButton: query("#saveConfig input.cancel"),
  };

  const { container, textarea, table, infohelp, header, advForm, saveButton, cancelButton } = selectors;

  d.body.classList.add("js");
  textarea.style.display = "none";
  infohelp.setAttribute("colspan", "3");

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
  Object.assign(newKeyCell, {
    className: "newKey",
    contentEditable: true,
    spellcheck: "false"
  });
  Object.assign(newValueCell, {
    className: "newValue",
    contentEditable: true,
    spellcheck: "false"
  });
  newDelCell.className = "delete";

  const firstConfigRow = table.query(".configline");
  if (firstConfigRow) { firstConfigRow.insertAdjacentElement("beforebegin", addNewRow); }

  const observer = new MutationObserver(mutationsList => {
    mutationsList.forEach(mutation => {
      mutation.addedNodes.forEach(node => {
        if (node.tagName === "TD") updateTextarea();
      });
    });
  });

  observer.observe(table, { childList: true, subtree: true });

  const updateTextarea = () => {
    const updatedItems = [];
    const rows = table.queryAll(".configline");
    rows.forEach(row => {
      const key = row.query(".key").textContent.trim();
      const value = row.query(".value").textContent.trim();
      if (key) updatedItems.push(`${key}=${value}`);
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

  const addFilter = () => {
    const advFilter = d.createElement("span");
    advFilter.id = "advfilter";

    const filterInput = d.createElement("input");
    filterInput.type = "text";
    advFilter.appendChild(filterInput);

    filterInput.addEventListener("input", event => {
      const filterValue = event.target.value.toLowerCase();
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
      filterValue = "";
      table.queryAll(".configline").forEach(row => row.style.display = "table-row");
      addNewRow.scrollIntoView();
    });

    advFilter.appendChild(clearFilter);
    header.appendChild(advFilter);
  };
  addFilter();

  const doSave = event => {
    const modalActive = query("#modalActive");
    if (modalActive) {return;}
    updateTextarea();
    const newKey = newKeyCell.textContent.trim();
    const newValue = newValueCell.textContent.trim();

    if (newValue && !newKey) {
      if (theme === "dark") {
        modal("No keyname provided for the submitted value.<br>Please supply a keyname.");
      } else {
        alert("No keyname provided for the submitted value.<br>Please supply a keyname.");
      }
      newKeyCell.focus();
      return;
    }

    if (newKey) {
      const existingKey = configItems.some(item => item[0] === newKey);
      if (existingKey) {
        if (theme === "dark") {
          modal(`Duplicate key <b>${newKey}</b> submitted. Please modify the existing key.`);
        } else {
          alert(`Duplicate key <b>${newKey}</b> submitted. Please modify the existing key.`);
        }
        newKeyCell.textContent = "";
        newValueCell.textContent = "";
        const filterInput = query("#advfilter input");
        filterInput.value = newKey;
        filterInput.dispatchEvent(new Event("input", { bubbles: true }));
        return;
      }
      submitNewKeyValue();
    }

    if (event.key === "Enter") {
      event.preventDefault();
      saveButton.click();
    } else {advForm.requestSubmit(saveButton);}
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

  const resetForm = event => {
    event.preventDefault();
    textarea.value = query("input[name=nofilter_oldConfig]").value;
    advForm.requestSubmit(saveButton);
  };

  cancelButton.addEventListener("click", resetForm);
  saveButton.addEventListener("click", event => {
    event.preventDefault();
    doSave(event);
  });

  if (theme === "dark") {
    let statusAdded = false;
    const floodfillStatus = () => {
      const h3ff = query("#ffconf");
      const info = d.createElement("span");
      info.id = "ffstatus";
      const ffstatus = query("#floodfillconfig .infohelp").textContent.match(/\(.*?\)/)[0].replace(/[\(\).]/g, "");
      info.textContent = ffstatus;
      if (!statusAdded) h3ff.appendChild(info);
      statusAdded = true;
    };

    requestAnimationFrame(floodfillStatus);
    query("#ffconf").addEventListener("click", floodfillStatus);
    window.addEventListener("resize", floodfillStatus);
  }
}

document.addEventListener("DOMContentLoaded", init);