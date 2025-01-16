/* I2P+ deleteHosts.js for SusiDNS by dr|3d */
/* Provide hooks for rudimentary command line host deletion */
/* License: AGPL3 or later */

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

function extractMessageContent(html) {
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, "text/html");
  const messageDiv = doc.getElementById("messages");
  return messageDiv ? messageDiv.innerHTML : "Server Error";
}

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