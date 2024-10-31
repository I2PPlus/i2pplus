/* I2P+ viewprofile.js by dr|z3d */
/* Tabulate individual profiles */
/* License: AGPL3 or later */

(function() {
  document.addEventListener("DOMContentLoaded", () => {
    const viewprofile = document.getElementById("viewprofile");
    const profile = document.querySelector("#viewprofile pre").innerText;
    const lines = profile.split("\n");
    let tableHTML = "<table>";

    lines.forEach((line) => {
      if (line.startsWith("# ---")) {
      } else if (line.startsWith("# ") && line.includes(":")) {
        const [key, ...rest] = line.slice(2).split(":");
        const value = rest.join(":").trim();
        const cleanedValue = cleanValue(value);

        if (key.trim() === "Period") {
          const concatenatedValue = key + ": " + cleanedValue;
          tableHTML += "<tr><th class='subheading' colspan='2'>" + concatenatedValue + "</th></tr>";
        } else {tableHTML += "<tr><td>" + key + "</td><td>" + cleanedValue + "</td></tr>";}
      } else if (line.startsWith("# ") && !line.includes(":")) {
        const sectionTitle = line.slice(2).trim();
        tableHTML += "<tr><th colspan='2'>" + sectionTitle + "</th></tr>";
      }
    });

    tableHTML += "</table>";
    viewprofile.innerHTML = tableHTML;
    viewprofile.classList.add("tabulated");
  });

  function cleanValue(value) {
    let cleanedValue = value.replace(/\[.*?\]/g, '').trim();
    cleanedValue = cleanedValue.replace(/0\.0/g, '0');
    if (cleanedValue.endsWith('.0')) {cleanedValue = cleanedValue.slice(0, -2);}
    return cleanedValue;
  }

})();