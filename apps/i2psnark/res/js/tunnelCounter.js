/* I2P+ tunnelCounter.js for I2PSnark by dr|z3d */
/* Counts active in/out snark tunnels and inserts them in the UI */
/* Requires I2P+, doesn't work with standalone I2PSnark */
/* License: AGPL3 or later */

let cachedTunnelCounts = null;
let inLabel = "";
let outLabel = "";

async function fetchTunnelData() {
  const url = "/configtunnels";
  const timeout = 10 * 1000;
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) throw new Error("Network response was not ok: " + response.statusText);
    const doc = new DOMParser().parseFromString(await response.text(), "text/html");
    const data = {
      inCount: doc.querySelector("#snarkIn")?.textContent.trim(),
      inHops: doc.querySelector("#snarkInHops")?.textContent.trim(),
      outCount: doc.querySelector("#snarkOut")?.textContent.trim(),
      outHops: doc.querySelector("#snarkOutHops")?.textContent.trim()
    };

    if (Object.values(data).every(Boolean)) {
      return data;
    } else {
      console.error("One or more tunnel count elements not found in the response.");
    }
  } catch (error) {
    if (error.name !== 'AbortError') {
      console.error("Error fetching or processing data:", error);
    }
  } finally {
    clearTimeout(id);
  }
  return null;
}

async function getSnarkTunnelCount() {
  if (cachedTunnelCounts && Date.now() - cachedTunnelCounts.timestamp < 60000) {
    try {
      const response = await fetch("/configtunnels", { method: 'HEAD' });
      if (response.ok && new Date(response.headers.get('last-modified')) > new Date(cachedTunnelCounts.timestamp)) {
        cachedTunnelCounts.data = await fetchTunnelData();
        cachedTunnelCounts.timestamp = Date.now();
      }
    } catch (error) {
      console.error("Error checking for a fresher version:", error);
    }
    return cachedTunnelCounts?.data || null;
  }

  cachedTunnelCounts = { timestamp: Date.now(), data: await fetchTunnelData() };
  return cachedTunnelCounts.data;
}

function updateTunnelCounts(result) {
  if (result) {
    injectCss();
    const snarkInCount = document.querySelector("#tnlInCount .badge");
    const snarkOutCount = document.querySelector("#tnlOutCount .badge");
    if (snarkInCount && snarkOutCount) {
      inLabel = `${result.inCount} / ${result.inHops}`;
      outLabel = `${result.outCount} / ${result.outHops}`;
    } else {
      console.error("Badge elements not found for updating counts.");
    }
  } else {
    console.warn("No result to update tunnel counts.");
  }
}

function injectCss() {
  const css = document.createElement("style");
  const styles = `#tnlInCount .badge::after{content:"${inLabel}"}#tnlOutCount .badge::after{content:"${outLabel}"}`;
  css.textContent = styles;

  const existingCss = document.head.querySelector("#tc");
  if (!existingCss) {
    css.id = "tc";
    document.head.appendChild(css);
  } else {
    existingCss.textContent = styles;
  }
}

document.addEventListener("DOMContentLoaded", () => {
  getSnarkTunnelCount().then(result => {
    injectCss();
    updateTunnelCounts(result);
  });
  getSnarkTunnelCount().then(updateTunnelCounts);
  setInterval(() => getSnarkTunnelCount().then(updateTunnelCounts), Math.max(snarkRefreshDelay, 30) * 1000);
});