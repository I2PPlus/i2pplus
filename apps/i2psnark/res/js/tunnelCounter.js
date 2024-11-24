/* I2P+ tunnelCounter.js for I2PSnark by dr|z3d */
/* Counts active in/out snark tunnels and inserts them in the UI */
/* Requires I2P+, doesn't work with standalone I2PSnark */
/* License: AGPL3 or later */

const cachedTunnelCounts = { timestamp: 0, data: null };
let inLabel = "", outLabel = "";

async function fetchTunnelData(url = "/configtunnels", timeout = 10000) {
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) throw new Error(response.statusText);
    const doc = new DOMParser().parseFromString(await response.text(), "text/html");
    const data = { inCount: doc.querySelector("#snarkIn")?.textContent.trim(), outCount: doc.querySelector("#snarkOut")?.textContent.trim() };
    if (data.inCount && data.outCount) return data;
  } catch (error) {}
  finally { clearTimeout(id); }
  return null;
}

async function getSnarkTunnelCount(interval = 30000) {
  if (cachedTunnelCounts.timestamp + interval > Date.now()) return cachedTunnelCounts.data;
  cachedTunnelCounts.data = await fetchTunnelData();
  cachedTunnelCounts.timestamp = Date.now();
  return cachedTunnelCounts.data;
}

function updateTunnelCounts(result) {
  if (result) {
    const snarkInCount = document.querySelector("#tnlInCount .badge");
    const snarkOutCount = document.querySelector("#tnlOutCount .badge");
    if (snarkInCount && snarkOutCount) {
      [inLabel, outLabel] = [result.inCount, result.outCount];
      const styleTag = document.head.querySelector("#tc") || createStyleTag();
      const styles = `#tnlInCount .badge::after{content:"${inLabel}"}#tnlOutCount .badge::after{content:"${outLabel}"}`;
      if (styleTag.textContent !== styles) styleTag.textContent = styles;
    }
  }
}

function createStyleTag() {
  const styleTag = document.createElement("style");
  styleTag.id = "tc";
  document.head.appendChild(styleTag);
  return styleTag;
}

document.addEventListener("DOMContentLoaded", async () => {
  const result = await getSnarkTunnelCount();
  const refresh = snarkRefreshDelay !== null ? snarkRefreshDelay - 500 : 10000;
  updateTunnelCounts(result);
  setInterval(async () => {
    const result = await getSnarkTunnelCount();
    updateTunnelCounts(result);
  }, Math.max(refresh, 10000));
});