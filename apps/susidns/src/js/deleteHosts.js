/**
 * @module deleteHosts
 * @file I2P+ SusiDNS host deletion utility.
 * Provides command-line style host deletion from address books via URL parameters.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Performs an asynchronous deletion request for the specified hosts from the
 * router address book. Fetches the initial page to obtain a CSRF serial token,
 * then submits a POST request to delete the selected hosts.
 * @async
 * @function performDelete
 * @param {string[]} hosts - Array of host addresses to delete.
 * @returns {Promise<void>} Resolves when the deletion request completes and
 *   the result message has been displayed to the user.
 * @example
 * performDelete(["example.b32.i2p", "anotherhost.b32.i2p"]);
 */
async function performDelete(hosts) {
  const url = "/susidns/addressbook?book=router";
  const formData = new URLSearchParams();
  formData.append("book", "router");
  formData.append("serial", nonce);
  hosts.forEach(host => formData.append("checked", host.trim()));
  formData.append("action", "Delete Selected");

  try {
    const getResponse = await fetch(url, { method: "GET", credentials: "include" });
    if (!getResponse.ok) {
      throw new Error(`Failed to retrieve initial page: ${getResponse.status}`);
    }

    const cookies = document.cookie.split(";").map(cookie => cookie.trim());
    const jsessionid = cookies.find(cookie => cookie.startsWith("JSESSIONID="));
    const cookieHeader = jsessionid ? jsessionid : "";

    const parser = new DOMParser();
    const doc = parser.parseFromString(await getResponse.text(), "text/html");
    const serialInput = doc.querySelector('input[name="serial"]');
    const serial = serialInput ? serialInput.value : null;

    if (!serial) {throw new Error("Serial (nonce) not found in the initial response.");}

    formData.set("serial", serial);

    const response = await fetch(url, {
      method: "POST", body: formData,
      headers: {"Content-Type": "application/x-www-form-urlencoded; charset=utf-8", "Cookie": cookieHeader},
      credentials: "include"
    });

    if (response.ok) {
      const data = await response.text();
      const messageContent = extractMessageContent(data);

      if (messageContent.includes("Invalid form submission")) {
        displayMessage(messageContent, "error", hosts);
      } else {
        displayMessage(messageContent, "success", hosts);
      }
    } else {
      const errorData = await response.text();
      throw new Error(`Request failed with status ${response.status}: ${errorData}`);
    }
  } catch (error) {
    document.querySelector("h1").remove();
    displayMessage(error.message, "error", hosts);
  }
}

/**
 * Extracts the message content from the server response HTML by querying
 * the element with id "messages".
 * @function extractMessageContent
 * @param {string} html - Raw HTML string returned from the server.
 * @returns {string} The innerHTML of the messages element, or "Server Error"
 *   if the element is not found.
 */
function extractMessageContent(html) {
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, "text/html");
  const messageDiv = doc.getElementById("messages");
  return messageDiv ? messageDiv.innerHTML : "Server Error";
}

/**
 * Displays a status message to the user, optionally including a list of
 * affected hosts.
 * @function displayMessage
 * @param {string} message - The message text (may contain HTML) to display.
 * @param {string} className - CSS class name to apply (e.g. "success", "error", "warning").
 * @param {string[]} hosts - Array of host names to list alongside the message.
 * @returns {void}
 */
function displayMessage(message, className, hosts) {
  const messageElement = document.getElementById("message");
  messageElement.className = className;

  let hostsList = "";
  if (hosts && hosts.length > 0) {
    hostsList = `<br><strong>Hosts:</strong> ${hosts.join(", ")}`;
  }
  messageElement.innerHTML = `${message}${hostsList}`;
}

const urlParams = new URLSearchParams(window.location.search);
const hostsParam = urlParams.get("hosts");
const hosts = hostsParam ? hostsParam.split(",").filter(Boolean) : [];

if (hosts.length) {performDelete(hosts);}
else {displayMessage("Warning: No hosts specified in the URL.", "warning", []);}