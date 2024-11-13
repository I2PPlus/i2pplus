/* I2P+ tunnelCounter.js for I2PSnark by dr|z3d */
/* Counts active in/out snark tunnels and inserts them in the UI */
/* Requires I2P+, doesn't work with standalone I2PSnark */
/* License: AGPL3 or later */

async function getSnarkTunnelCount() {
  const url = "/configtunnels";

  try {
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error("Network response was not ok: " + response.statusText);
    }
    const html = await response.text();
    const parser = new DOMParser();
    const doc = parser.parseFromString(html, "text/html");
    const snarkInCount = doc.querySelector("#snarkIn");
    const snarkInHops = doc.querySelector("#snarkInHops");
    const snarkOutCount = doc.querySelector("#snarkOut");
    const snarkOutHops = doc.querySelector("#snarkOutHops");

    if (snarkInCount && snarkInHops && snarkOutCount && snarkOutHops) {
      const inCount = snarkInCount.textContent.trim();
      const inHops = snarkInHops.textContent.trim();
      const outCount = snarkOutCount.textContent.trim();
      const outHops = snarkOutHops.textContent.trim();
      return { inCount, inHops, outCount, outHops };
    } else {
      console.error("One or more tunnel count elements not found in the response.");
    }
  } catch (error) {
    console.error("Error fetching or processing data:", error);
  }
}

function updateTunnelCounts(result) {
  if (result) {
    const snarkIn = document.getElementById("tnlInCount");
    const snarkInCount = document.querySelector("#tnlInCount .badge");
    const snarkOut = document.getElementById("tnlOutCount");
    const snarkOutCount = document.querySelector("#tnlOutCount .badge");

    if (snarkInCount && snarkOutCount) {
      snarkIn.removeAttribute("hidden");
      snarkInCount.textContent = result.inCount + " / " + result.inHops;
      snarkOut.removeAttribute("hidden");
      snarkOutCount.textContent = result.outCount + " / " + result.outHops;
    } else {
      console.error("Badge elements not found for updating counts.");
    }
  } else {
    console.warn("No result to update tunnel counts.");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  getSnarkTunnelCount().then(result => {
    updateTunnelCounts(result);
  });
  setInterval(() => getSnarkTunnelCount().then(result => updateTunnelCounts(result)), Math.max(snarkRefreshDelay, 15) * 1000);
});