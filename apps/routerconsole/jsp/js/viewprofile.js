/* I2P+ viewprofile.js by dr|z3d */
/* Tabulate individual profiles and add section toggler */
/* License: AGPL3 or later */

(function() {
  document.addEventListener("DOMContentLoaded", () => {
    const viewprofile = document.getElementById("viewprofile");
    const profileContainer = document.querySelector("#viewprofile pre");
    const profile = document.querySelector("#viewprofile pre").innerText;
    const lines = profile.split("\n");
    profileContainer.style.display = "none";
    let tableHTML = "<table>";

    lines.forEach((line) => {
      if (line.startsWith("# ---")) {
      } else if (line.startsWith("# ") && line.includes(":")) {
        const [key, ...rest] = line.slice(2).split(":");
        const value = rest.join(":").trim();
        const cleanedValue = cleanValue(value);

        if (key.trim() === "Period") {
          const concatenatedValue = key + ": " + cleanedValue;
          tableHTML += "<tr class=subheading><th colspan=2>" + concatenatedValue + "</th></tr>";
        } else {tableHTML += "<tr><td>" + key + "</td><td>" + cleanedValue + "</td></tr>";}
      } else if (line.startsWith("# ") && !line.includes(":")) {
        const sectionTitle = line.slice(2).trim();
        tableHTML += "<tr class=section><th colspan=2>" + sectionTitle + "</th></tr>";
      }
    });

    tableHTML += "</table>";
    viewprofile.innerHTML = tableHTML;
    viewprofile.classList.add("tabulated");
    setTimeout(addToggles, 50);
  });

  function cleanValue(value) {
    let cleanedValue = value.replace(/\[.*?\]/g, '').trim();
    cleanedValue = cleanedValue.replace(/0\.0/g, '0');
    if (cleanedValue.endsWith('.0')) {cleanedValue = cleanedValue.slice(0, -2);}
    return cleanedValue;
  }

  function addToggles() {
    document.querySelectorAll(".section").forEach(section => {
      let next = section.nextElementSibling;
      while (next && !next.classList.contains("section")) {
        next.classList.add("hidden");
        next = next.nextElementSibling;
      }
    });

    document.querySelectorAll(".section").forEach(section => {
      section.addEventListener("click", function() {
        document.querySelectorAll(".section").forEach(otherSection => {
          if (otherSection !== this) {
            otherSection.classList.remove("expanded");
            let next = otherSection.nextElementSibling;
            while (next && !next.classList.contains("section")) {
              next.classList.add("hidden");
              next = next.nextElementSibling;
            }
          }
        });
        this.classList.toggle("expanded");
        let next = this.nextElementSibling;
        while (next && !next.classList.contains("section")) {
          next.classList.toggle("hidden");
          next = next.nextElementSibling;
        }
        if (this.classList.contains("expanded")) {window.scrollTo(0, this.offsetTop);}
        else {window.scrollTo(0,0);}
      });
    });
  }

})();
