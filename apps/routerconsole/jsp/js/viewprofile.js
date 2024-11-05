/* I2P+ viewprofile.js by dr|z3d */
/* Tabulate individual profiles and add section toggler */
/* License: AGPL3 or later */

(function() {
  document.addEventListener("DOMContentLoaded", () => {
    const viewprofile = document.getElementById("viewprofile");
    const profileContainer = document.querySelector("#viewprofile pre");
    const profile = profileContainer.innerText;
    const lines = profile.split("\n");
    profileContainer.style.display = "none";

    let manualSpeedScoreAdjustment = 0; // Initialize the adjustment value
    let speedValue = null; // Initialize the speed value

    // First pass to collect all relevant values
    lines.forEach((line) => {
      if (line.startsWith("# ---")) {} // skip
      else if (line.startsWith("# ") && line.includes(":")) {
        const [key, ...rest] = line.slice(2).split(":");
        const value = rest.join(":").trim();
        const cleanedKey = key.replace("(ms since the epoch)", "").trim();
        const cleanedValue = cleanValue(value);

        if (cleanedKey === "Manual Speed Score adjustment") {
          manualSpeedScoreAdjustment = parseFloat(cleanedValue);
        }

        if (cleanedKey === "Speed") {
          speedValue = parseFloat(cleanedValue);
        }
      }
    });

    // Second pass to generate the table HTML
    let tableHTML = "<table>";
    lines.forEach((line) => {
      if (line.startsWith("# ---")) {} // skip
      else if (line.startsWith("# ") && line.includes(":")) {
        const [key, ...rest] = line.slice(2).split(":");
        const value = rest.join(":").trim();
        const cleanedKey = key.replace("(ms since the epoch)", "").replace("(ms)", "").trim();
        const cleanedValue = cleanValue(value);

        if (cleanedKey === "Speed") {
          const speed = speedValue - manualSpeedScoreAdjustment;
          if (speed > 0) { // Only display if speed is greater than 0 B/s
            let convertedSpeed;
            if (!isNaN(speed)) {
              if (speed >= 1024) {
                convertedSpeed = (speed / 1024).toFixed(2); // Convert to MB/s
                tableHTML += "<tr class=stat><td>" + cleanedKey + "</td><td>" + convertedSpeed + " MB/s</td></tr>";
              } else {
                convertedSpeed = speed.toFixed(0);
                tableHTML += "<tr class=stat><td>" + cleanedKey + "</td><td>" + convertedSpeed + " B/s</td></tr>";
              }
            }
          }
        } else if (cleanedKey === "Time of last failed lookup from peer" ||
            cleanedKey === "Time of last successful lookup from peer" ||
            cleanedKey === "Time of last failed store to peer" ||
            cleanedKey === "Time of last successful store to peer") {
          const convertedDate = convertEpochToReadableDate(cleanedValue);
          tableHTML += "<tr class=stat><td>" + cleanedKey + "</td><td>" + convertedDate + "</td></tr>";
        } else if (cleanedKey === "Period") {
          const concatenatedValue = cleanedKey + ": " + cleanedValue;
          tableHTML += "<tr class='subheading period'><th colspan=2>" + concatenatedValue + "</th></tr>";
        } else if (cleanedKey === "Average peer response time") {
          const responseTime = parseFloat(cleanedValue);
          let formattedResponseTime;
          if (!isNaN(responseTime)) {
            if (responseTime > 1000) {
              formattedResponseTime = (responseTime / 1000).toFixed(1) + " seconds";
            } else {
              formattedResponseTime = responseTime + " milliseconds";
            }
          }
          tableHTML += "<tr class=stat><td>" + cleanedKey + "</td><td>" + formattedResponseTime + "</td></tr>";
        } else {
          tableHTML += "<tr class=stat><td>" + cleanedKey + "</td><td>" + cleanedValue + "</td></tr>";
        }
      } else if (line.startsWith("# ") && !line.includes(":")) {
        const sectionTitle = line.slice(2).trim();
        const [text, title] = extractTooltip(sectionTitle);
        if (text === "Router Information") {
          tableHTML += "<tr class='section heading ri expanded'><th colspan='2' title='" + title + "'>" + text + "</th></tr>";
        } else if (text === "NetDb History") {
          tableHTML += "<tr class='section heading db expanded'><th colspan='2' title='" + title + "'>" + text + "</th></tr>";
        } else if (text === "Tunnel History") {
          tableHTML += "<tr class='section heading tnl expanded'><th colspan='2' title='" + title + "'>" + text + "</th></tr>";
        } else {
          tableHTML += "<tr class=section><th colspan='2' title='" + title + "'>" + text + "</th></tr>";
        }
      }
    });

    tableHTML += "</table>";
    viewprofile.innerHTML = tableHTML;
    viewprofile.classList.add("tabulated");
    setTimeout(addToggles, 50);
  });

  function extractTooltip(title) {
    const match = title.match(/\[(.*?)\]/);
    if (match) {
      const titleContent = match[1];
      const text = title.replace(match[0], "").trim();
      return [text, titleContent];
    }
    return [title, ""];
  }

  function cleanValue(value) {
    let cleanedValue = value.replace(/\[.*?\]/g, "").trim();
    cleanedValue = cleanedValue.replace(/GMT/g, "");
    cleanedValue = cleanedValue.replace(/_/g, " ");
    cleanedValue = cleanedValue.replace(/0\.0/g, "0");
    cleanedValue = cleanedValue.replace(/9999999/g, "9999999 (Low latency)")
    if (cleanedValue.endsWith(".0")) {cleanedValue = cleanedValue.slice(0, -2);}
    return cleanedValue;
  }

  function convertEpochToReadableDate(epoch) {
    if (epoch === "0") {return "Never";}
    const date = new Date(parseInt(epoch));
    const weekdays = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

    const dayOfWeek = weekdays[date.getUTCDay()];
    const month = months[date.getUTCMonth()];
    const dayOfMonth = date.getUTCDate();
    const hours = String(date.getUTCHours()).padStart(2, '0');
    const minutes = String(date.getUTCMinutes()).padStart(2, '0');
    const seconds = String(date.getUTCSeconds()).padStart(2, '0');

    return `${dayOfWeek} ${month} ${dayOfMonth} ${hours}:${minutes}:${seconds} ${date.getFullYear()}`;
  }

  function convertBtoKB(bytes) {
    return (bytes / 1024).toFixed(2);
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
      this.classList.toggle("expanded");
      let next = this.nextElementSibling;
      while (next && !next.classList.contains("section")) {
        next.classList.toggle("hidden");
        next = next.nextElementSibling;
      }
      if (this.classList.contains("expanded")) {
        window.scrollTo(0, this.offsetTop);
      } else {
        window.scrollTo(0, 0);
      }
    });
  });

  // Expand the .ri, .tnl, and .db sections by default
  const sectionsToExpand = document.querySelectorAll(".ri, .tnl, .db");
  if (sectionsToExpand.length > 0) {
    Array.from(sectionsToExpand).forEach(section => {
      section.classList.add("expanded");
      let next = section.nextElementSibling;
      while (next && !next.classList.contains("section")) {
        next.classList.remove("hidden");
        next = next.nextElementSibling;
      }
    });
  }
  setTimeout(() => viewprofile.removeAttribute("hidden"), 100);
}

})();