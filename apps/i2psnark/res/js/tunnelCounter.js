/**
 * @module tunnelCounter
 * @file tunnelCounter.js - Counts active in/out I2PSnark tunnels and displays them in the UI.
 * @description Periodically fetches tunnel data from the I2P tunnel configuration page, counts
 * active inbound and outbound snark tunnels, and injects the counts into the UI via CSS
 * pseudo-element content. Fetches and extracts through the snarkWork worker, falling back
 * to a direct fetch when the worker is unavailable. Requires I2P+ and does not work with
 * standalone I2PSnark.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {fetchTunnelCounts, isDocumentVisible} from "./refreshTorrents.js";
import {extractTunnelCounts} from "./refreshPayload.js";

/**
 * @type {Object}
 * @description Cached tunnel count data with a timestamp to avoid excessive fetches.
 * @property {number} timestamp - Timestamp of the last successful fetch in milliseconds.
 * @property {?Object} data - The cached tunnel count data, or null if not yet fetched.
 */
const cachedTunnelCounts = { timestamp: 0, data: null };

/**
 * @type {string}
 * @description Label text for the inbound tunnel count badge.
 */
let inLabel = "";

/**
 * @type {string}
 * @description Label text for the outbound tunnel count badge.
 */
let outLabel = "";

/**
 * @async
 * @function fetchTunnelData
 * @description Fetches the I2P tunnel configuration page and extracts the snark inbound
 * and outbound tunnel counts from the #snarkIn and #snarkOut elements. Prefers the
 * snark worker so the download and extraction happen off the main thread, falling back
 * to a direct fetch with a timeout when the worker is unavailable.
 * @param {string} [url="/configtunnels"] - The URL of the tunnel configuration page.
 * @param {number} [timeout=10000] - Request timeout in milliseconds for the direct fallback.
 * @returns {Promise<?Object>} An object with inCount and outCount properties, or null on failure.
 */
async function fetchTunnelData(url = "/configtunnels", timeout = 10000) {
  if (!isDocumentVisible) {return;}
  try {
    const counts = await fetchTunnelCounts();
    if (counts) {return counts;}
    return null;
  } catch (error) {}
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) { throw new Error(response.statusText); }
    return extractTunnelCounts(await response.text());
  } catch (error) {}
  finally { clearTimeout(id); }
  return null;
}

/**
 * @async
 * @function getSnarkTunnelCount
 * @description Returns cached tunnel count data if within the specified interval,
 * otherwise fetches fresh data from the server and updates the cache.
 * @param {number} [interval=30000] - Minimum interval in milliseconds between fetches.
 * @returns {Promise<?Object>} The tunnel count data with inCount and outCount, or null on failure.
 */
async function getSnarkTunnelCount(interval = 30000) {
  if (cachedTunnelCounts.timestamp + interval > Date.now()) return cachedTunnelCounts.data;
  cachedTunnelCounts.data = await fetchTunnelData();
  cachedTunnelCounts.timestamp = Date.now();
  return cachedTunnelCounts.data;
}

/**
 * @function updateTunnelCounts
 * @description Updates the UI badges for inbound and outbound tunnel counts by injecting
 * CSS content rules. Creates a style element if one doesn't exist, and only updates
 * the content if the values have changed.
 * @param {?Object} result - The tunnel count data with inCount and outCount properties.
 * @returns {void}
 */
function updateTunnelCounts(result) {
  if (result) {
    const snarkInCount = document.querySelector("#tnlInCount .badge");
    const snarkOutCount = document.querySelector("#tnlOutCount .badge");
    if (snarkInCount && snarkOutCount) {
      [inLabel, outLabel] = [result.inCount, result.outCount];
      const styleTag = document.head.querySelector("#tc") || createStyleTag();
      const styles = `#tnlInCount .badge::after{content:"${inLabel}"}#tnlOutCount .badge::after{content:"${outLabel}"}`;
      if (styleTag.textContent !== styles) { styleTag.textContent = styles; }
    }
  }
}

/**
 * @function createStyleTag
 * @description Creates and appends a <style> element with id "tc" to the document head.
 * Used for injecting CSS rules that display tunnel counts.
 * @returns {HTMLStyleElement} The newly created style element.
 */
function createStyleTag() {
  const styleTag = document.createElement("style");
  styleTag.id = "tc";
  document.head.appendChild(styleTag);
  return styleTag;
}

document.addEventListener("DOMContentLoaded", async () => {
  const result = await getSnarkTunnelCount();
  const configured = typeof snarkRefreshDelay === "number" ? snarkRefreshDelay - 500 : 10000;
  const refresh = Number.isFinite(configured) ? Math.max(configured, 10000) : 10000;
  updateTunnelCounts(result);
  setInterval(async () => {
    const result = await getSnarkTunnelCount();
    updateTunnelCounts(result);
  }, refresh);
});
