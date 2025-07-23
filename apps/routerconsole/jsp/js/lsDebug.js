/* I2P+ lsDebug.js by dr|z3d */
/* Consolidate debug and keyspace tables on Leasesets debug page */
/* License: AGPL3 or later */

function lsDebug() {
  const getById = id => document.getElementById(id);
  const sel = (el, sel) => el?.querySelector(sel);
  const text = el => el?.textContent.trim();

  const rapRow = getById("rapLS");
  const debugTbody = document.querySelector("#leasesetdebug tbody");
  const keyspaceTbody = document.querySelector("#leasesetKeyspace tbody");
  const leasesetKeyspace = getById("leasesetKeyspace");
  if (!(rapRow && debugTbody && keyspaceTbody && leasesetKeyspace)) return;

  const medianRow = document.querySelector("#leasesetKeyspace #medianDistance");
  if (medianRow) {
    const dataCell = sel(rapRow, "td:nth-child(2)");
    if (dataCell) {
      dataCell.removeAttribute("colspan");
      const newDataCell = document.createElement("td");
      newDataCell.textContent = dataCell.textContent;
      dataCell.replaceWith(newDataCell);
      const medianLabel = sel(medianRow, "td:nth-child(1)").cloneNode(true);
      const medianValue = sel(medianRow, "td:nth-child(2)").cloneNode(true);
      rapRow.appendChild(medianLabel);
      rapRow.appendChild(medianValue);
      medianValue.setAttribute("colspan", "2");
    }
    medianRow.remove();
  }

  const rows = Array.from(keyspaceTbody.querySelectorAll("tr"));
  if (rows.length >= 2) {
    const [a, b] = [rows.at(-2), rows.at(-1)];
    const [labelA, valA] = [text(sel(a, "td:nth-child(1)")), text(sel(a, "td:nth-child(2)"))];
    const [labelB, valB] = [text(sel(b, "td:nth-child(1)")), text(sel(b, "td:nth-child(2)"))];

    const newRow = document.createElement("tr");
    newRow.id = "estimatesCombined";

    const td = (html, isHTML = false) => {
      const el = document.createElement("td");
      if (isHTML) el.innerHTML = html;
      else el.textContent = html;
      return el;
    };

    newRow.appendChild(td(`<b>${labelA}</b>`, true));
    newRow.appendChild(td(valA));
    newRow.appendChild(td(`<b>${labelB}</b>`, true));
    newRow.appendChild(td(valB));

    a.remove();
    b.remove();
    keyspaceTbody.appendChild(newRow);
  }

  keyspaceTbody.querySelectorAll("tr").forEach(row => debugTbody.appendChild(row));

  const estimatedFF = debugTbody.querySelector("#estimatedFF");
  const estimatedLS = debugTbody.querySelector("#estimatedLS");
  if (estimatedFF && estimatedLS) {
    const combinedRow = document.createElement("tr");
    combinedRow.id = "estimatesCombined";

    const labelFF = sel(estimatedFF, "td:nth-child(1)").cloneNode(true);
    const valueFF = sel(estimatedFF, "td:nth-child(2)").cloneNode(true);
    const labelLS = sel(estimatedLS, "td:nth-child(1)").cloneNode(true);
    const valueLS = sel(estimatedLS, "td:nth-child(2)").cloneNode(true);

    valueFF.removeAttribute("colspan");
    valueLS.removeAttribute("colspan");

    const combinedLabel = document.createElement("td");
    combinedLabel.textContent = labelFF.textContent + " and " + labelLS.textContent;

    const combinedValue = document.createElement("td");
    combinedValue.setAttribute("colspan", "2");
    combinedValue.innerHTML = `<b>${valueFF.textContent} ${valueLS.textContent}</b>`;

    combinedRow.appendChild(combinedLabel);
    combinedRow.appendChild(combinedValue);

    estimatedFF.replaceWith(combinedRow);
    estimatedLS.remove();
  }

  leasesetKeyspace.style.display = "none";
};

export {lsDebug};