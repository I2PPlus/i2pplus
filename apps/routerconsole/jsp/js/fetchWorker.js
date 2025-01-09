const MAX_CONCURRENT_REQUESTS = 32;
const MIN_INTERVAL = 500;
let activeRequests = 0;
let fetchQueue = [];
const lastRequestTimeMap = new Map();

self.addEventListener("message", async function(event) {
  const { url, force = false } = event.data;
  const now = Date.now();
  const lastRequestTime = lastRequestTimeMap.get(url);

  if (!force && lastRequestTime !== undefined && now - lastRequestTime < MIN_INTERVAL) {
    console.log('Skipping request for URL:', url);
    return;
  }

  if (activeRequests < MAX_CONCURRENT_REQUESTS) {
    activeRequests++;
    processFetchRequest(url);
  } else {
    fetchQueue.push(url);
  }
});

async function processFetchRequest(url) {
  const now = Date.now();
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: { Accept: "text/html, application/octet-stream" },
    });

    if (response.ok) {
      if (response.headers.get("Content-Type").includes("text/html")) {
        const responseText = await response.text();
        self.postMessage({ responseText, isDown: false });
      } else {
        const responseBlob = await response.blob();
        self.postMessage({ responseBlob, isDown: false });
      }
    } else {
      self.postMessage({ responseBlob: null, isDown: true });
    }
  } catch (error) {
    console.error("Error:", error);
     setTimeout(() => {
      self.postMessage({ responseBlob: null, isDown: true });
    }, 3000);
  } finally {
    activeRequests--;
    processNextFetchRequest();
    lastRequestTimeMap.set(url, now);
  }
}

function processNextFetchRequest() {
  if (fetchQueue.length > 0 && activeRequests < MAX_CONCURRENT_REQUESTS) {
    const url = fetchQueue.shift();
    activeRequests++;
    processFetchRequest(url);
  }
}