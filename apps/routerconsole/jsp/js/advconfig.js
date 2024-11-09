const advConfig = function() {

  const container = document.querySelector("td.tabletextarea");
  const textarea = document.querySelector("textarea#advancedsettings");
  const table = document.getElementById("advconf");
  const infohelp = document.querySelector("#advconf td.infohelp");

  textarea.style.display = "none";
  infohelp.setAttribute("colspan", "3");

  const items = textarea.value.split("\n");

  const parentRow = container.parentNode;
  while (parentRow.firstChild && parentRow.firstChild.firstChild !== textarea) {
    parentRow.removeChild(parentRow.firstChild);
  }

  const configItems = items
    .map(function(item) {
      return item.split("=");
    })
    .filter(function(parts) {
      return parts.length === 2;
    })
    .sort(function(a, b) {
      return a[0].localeCompare(b[0]);
    });

  let maxLength = 0;
  configItems.forEach(function(item) {
    maxLength = Math.max(maxLength, item[0].length);
  });

  const saveCancelRow = table.querySelector("#advconf tr:last-child");
  saveCancelRow.querySelector("td").setAttribute("colspan", "3");
  let lastConfigRow = null;

  configItems.forEach(function(item) {
    const row = document.createElement("tr");
    const keyCell = document.createElement("td");
    const valueCell = document.createElement("td");
    const delCell = document.createElement("td");
    keyCell.textContent = item[0];
    valueCell.textContent = item[1];
    keyCell.setAttribute("contenteditable", true);
    valueCell.setAttribute("contenteditable", true);
    keyCell.setAttribute("spellcheck", "false");
    valueCell.setAttribute("spellcheck", "false");
    keyCell.className = "key";
    valueCell.className = "value";
    row.className = "configline";
    delCell.className = "delete";
    row.appendChild(keyCell);
    row.appendChild(valueCell);
    row.appendChild(delCell);
    delCell.textContent = "delete";
    lastConfigRow ? lastConfigRow.insertAdjacentElement("afterend", row) : saveCancelRow.insertAdjacentElement("beforebegin", row);
    lastConfigRow = row;
  });

  const addNewRow = document.createElement("tr");
  addNewRow.id = "addNew";
  const newKeyCell = document.createElement("td");
  const newValueCell = document.createElement("td");
  const newDelCell = document.createElement("td");
  addNewRow.appendChild(newKeyCell);
  addNewRow.appendChild(newValueCell);
  addNewRow.appendChild(newDelCell);
  newKeyCell.className = "newKey";
  newKeyCell.setAttribute("contenteditable", true);
  newKeyCell.setAttribute("spellcheck", "false");
  newValueCell.className = "newValue";
  newValueCell.setAttribute("contenteditable", true);
  newValueCell.setAttribute("spellcheck", "false");
  newDelCell.className = "delete";
  newDelCell.textContent = "delete";

  const firstConfigRow = table.querySelector(".configline");
  if (firstConfigRow) {firstConfigRow.insertAdjacentElement("beforebegin", addNewRow);}
  else {saveCancelRow.insertAdjacentElement("beforebegin", addNewRow);}

  const observer = new MutationObserver(function(mutationsList) {
    mutationsList.forEach(function(mutation) {
      mutation.addedNodes.forEach(function(node) {
        if (node.tagName === "TD") {updateTextarea();}
      });
    });
  });

  observer.observe(container, { childList: true, subtree: true });

  const updateTextarea = function() {
    const updatedItems = [];
    const rows = document.querySelectorAll(".configline");
    rows.forEach(function(row) {
      const keyTd = row.children[0];
      const valueTd = row.children[1];
      const key = keyTd.textContent.trim();
      const value = valueTd.textContent.trim();
      if (key) {updatedItems.push(key + "=" + value);}
    });
    textarea.value = updatedItems.join("\n");
  };

  table.addEventListener("click", function(event) {
    const target = event.target;
    if (target.classList.contains("delete")) {
      const row = target.closest("tr");
      if (row.id !== "addNew") {
        row.remove();
        updateTextarea();
      } else {
         document.querySelector(".newKey").textContent = "";
         document.querySelector(".newValue").textContent = "";
      }
    }
  });

  const submitNewKeyValue = function() {
    const newKey = newKeyCell.textContent.trim();
    const newValue = newValueCell.textContent.trim();
    if (newKey) {
      const newRow = document.createElement("tr");
      const keyCell = document.createElement("td");
      const valueCell = document.createElement("td");
      const delCell = document.createElement("td");

      keyCell.textContent = newKey;
      valueCell.textContent = newValue;
      keyCell.setAttribute("contenteditable", true);
      valueCell.setAttribute("contenteditable", true);
      keyCell.setAttribute("spellcheck", "false");
      valueCell.setAttribute("spellcheck", "false");
      keyCell.className = "key";
      valueCell.className = "value";
      newRow.className = "configline";
      delCell.className = "delete";
      delCell.textContent = "delete";
      newRow.appendChild(keyCell);
      newRow.appendChild(valueCell);
      newRow.appendChild(delCell);
      addNewRow.insertAdjacentElement("beforebegin", newRow);
      newKeyCell.textContent = "";
      newValueCell.textContent = "";
      updateTextarea();
    }
  };

  const filterInput = document.createElement("input");
  filterInput.setAttribute("type", "text");
  document.querySelector("h3#advancedconfig").appendChild(filterInput);
  filterInput.addEventListener("input", function(event) {
    const filterValue = event.target.value.toLowerCase();
    const rows = document.querySelectorAll(".configline");
    rows.forEach(function(row) {
      const key = row.querySelector(".key").textContent.toLowerCase();
      const value = row.querySelector(".value").textContent.toLowerCase();
      if (key.includes(filterValue) || value.includes(filterValue)) {row.style.display = "table-row";}
      else {row.style.display = "none";}
    });
  });

  const advForm = document.querySelector("#advancedconfig+form");
  const saveButton = advForm.querySelector("input[type=submit]");
  const cancelButton = advForm.querySelector("input.cancel");

  advForm.addEventListener("submit", function(event) {updateTextarea();});

  document.addEventListener("keydown", function(event) {
    if (event.key === "Enter") { // save when Return is pressed
      event.preventDefault();
      updateTextarea();
      const newKey = document.querySelector("#addNew .newKey");
      if (newKey && newKey.textContent.trim()) {submitNewKeyValue();}
      saveButton.click();
    }
    if (event.key === "Escape") {resetForm();} // reset when Escape is pressed
  });

  function resetForm() {
    event.preventDefault();
    textarea.value = document.querySelector("input[name=nofilter_oldConfig]").value;
    advForm.submit();
  };

  cancelButton.addEventListener("click", function(event) {resetForm();});

}

document.addEventListener("DOMContentLoaded", advConfig);