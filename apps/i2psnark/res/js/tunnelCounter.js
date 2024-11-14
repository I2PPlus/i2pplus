/* I2P+ tunnelCounter.js for I2PSnark by dr|z3d */
/* Counts active in/out snark tunnels and inserts them in the UI */
/* Requires I2P+, doesn"t work with standalone I2PSnark */
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
      outCount: doc.querySelector("#snarkOut")?.textContent.trim(),
    };
    if (Object.values(data).every(Boolean)) {return data;}
  } catch (error) {}
  finally {clearTimeout(id);}
  return null;
}

async function getSnarkTunnelCount() {
  const interval = 30 * 1000;
  if (cachedTunnelCounts && Date.now() - cachedTunnelCounts.timestamp < interval) {
    return cachedTunnelCounts.data;
  }
  cachedTunnelCounts = { timestamp: Date.now(), data: await fetchTunnelData() };
  return cachedTunnelCounts.data;
}

function updateTunnelCounts(result) {
  if (result) {
    const snarkInCount = document.querySelector("#tnlInCount .badge");
    const snarkOutCount = document.querySelector("#tnlOutCount .badge");
    if (snarkInCount && snarkOutCount) {
      inLabel = `${result.inCount}`;
      outLabel = `${result.outCount}`;
      let styleTag = document.head.querySelector("#tc");
      const styles = `#tnlInCount .badge::after{content:"${inLabel}"}#tnlOutCount .badge::after{content:"${outLabel}"}`;
      if (!styleTag) {
        styleTag = document.createElement("style");
        styleTag.id = "tc";
        document.head.appendChild(styleTag);
      }
      if (styleTag.textContent !== styles) {styleTag.textContent = styles;}
    }
  }
}

document.addEventListener("DOMContentLoaded", async () => {
  const result = await getSnarkTunnelCount();
  const refresh = snarkRefreshDelay !== null ? snarkRefreshDelay - 500 : 10*1000;
  updateTunnelCounts(result);
  setInterval(async () => {
    const result = await getSnarkTunnelCount();
    updateTunnelCounts(result);
  }, Math.max(refresh, 10*1000));
});