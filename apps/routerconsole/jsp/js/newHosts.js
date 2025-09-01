/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

export function newHosts() {
  const newHostsBadge = document.getElementById("newHosts");
  if (!newHostsBadge) return;
  if (theme !== "dark") {
    newHostsBadge.style.display = "none";
    return;
  }

  const period = 30 * 60 * 1000;
  let newHostsInterval;
  let tooltipInitialized = false;

  function getStoredData() {
    const key = "newHostsData";
    const rawData = localStorage.getItem(key);

    if (!rawData) {
      return { hostnames: [], count: 0, lastUpdated: null };
    }

    try {
      const parsed = JSON.parse(rawData);
      if (typeof parsed === "object" && !Array.isArray(parsed) && parsed !== null) {
        return parsed;
      } else {
        throw new Error("Data is not an object");
      }
    } catch (e) {
      console.warn("Invalid JSON in localStorage, clearing:", key);
      localStorage.removeItem(key);
      return { hostnames: [], count: 0, lastUpdated: null };
    }
  }

  function fetchNewHosts() {
    localStorage.setItem("newHostsLastFetch", Date.now());

    fetch("/susidns/log.jsp")
      .then(response => response.text())
      .then(html => {
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, "text/html");

        const now = new Date();
        const oneDayAgo = new Date(now.getTime() - 24 * 60 * 60 * 1000);

        const entries = Array.from(doc.querySelectorAll("li"));
        const newHostnames = entries.flatMap(entry => {
          const dateText = entry.querySelector(".date")?.textContent;
          if (!dateText) return [];

          const entryDate = new Date(dateText);
          if (entryDate < oneDayAgo) return [];

          const links = entry.querySelectorAll("a");
          return Array.from(links).map(a => ({
            hostname: new URL(a.href).hostname,
            timestamp: entryDate.getTime()
          }));
        });

        const storedData = getStoredData();
        const storedHostnames = storedData.hostnames || [];

        const hostnameMap = new Map();
        [...storedHostnames, ...newHostnames].forEach(h => {
          if (!hostnameMap.has(h.hostname) || h.timestamp > hostnameMap.get(h.hostname)?.timestamp) {
            hostnameMap.set(h.hostname, h);
          }
        });

        const allHostnames = Array.from(hostnameMap.values())
          .filter(h => h.timestamp >= oneDayAgo.getTime())
          .sort((a, b) => b.timestamp - a.timestamp);

        const limitedHostnames = allHostnames.slice(0, 10);
        const sortedHostnames = limitedHostnames.map(h => h.hostname).sort();

        const count = sortedHostnames.length;
        localStorage.setItem("newHostsData", JSON.stringify({
          count,
          lastUpdated: Date.now(),
          hostnames: limitedHostnames
        }));

        if (count > 10) {
          newHostsBadge.textContent = "10+";
        } else {
          newHostsBadge.textContent = count || "";
        }

        updateTooltip(sortedHostnames);
      })
      .catch(err => console.error("Failed to fetch new hosts:", err));
  }

  function getNewHosts() {
    const now = Date.now();
    const storedData = getStoredData();
    const lastFetch = parseInt(localStorage.getItem("newHostsLastFetch") || "0");

    if (
      storedData.lastUpdated &&
      now - storedData.lastUpdated < 60000 &&
      now - lastFetch < 30000
    ) {
      const { count, hostnames } = storedData;
      if (count > 0) {
        if (count > 10) newHostsBadge.textContent = "10+";
        else newHostsBadge.textContent = count;
        updateTooltip(hostnames.map(h => h.hostname));
      } else {
        newHostsBadge.textContent = "";
      }
    } else {
      fetchNewHosts();
    }
  }

  function updateTooltip(hostnames) {
    if (!newHostsBadge) return;

    const newHosts = document.getElementById("newHostsList");
    const newHostsTd = newHosts?.querySelector("td");

    if (!hostnames.length) {
      newHosts.hidden = true;
      if (newHostsTd) newHostsTd.innerHTML = "";
      return;
    }

    const newHostsList = hostnames.map(hostname => {
      const shortName = hostname.replace(".i2p", "");
      return `<a href="http://${hostname}" target="_blank">${shortName}</a>`;
    }).join("<br>");

    if (newHostsTd) newHostsTd.innerHTML = newHostsList;

    newHosts.hidden = true;

    if (!tooltipInitialized) {
      newHostsBadge.addEventListener("mouseenter", () => {
        newHosts.hidden = false;
        const services = document.getElementById("sb_services");
        services?.classList.add("tooltipped");
      }, { passive: true });

      const services = document.getElementById("sb_services");
      services?.addEventListener("mouseleave", () => {
        newHosts.hidden = true;
        services.classList.remove("tooltipped");
      }, { passive: true });

      tooltipInitialized = true;
    }
  }
  if (newHostsInterval) clearInterval(newHostsInterval);
  getNewHosts();
  newHostsInterval = setInterval(fetchNewHosts, period);
}