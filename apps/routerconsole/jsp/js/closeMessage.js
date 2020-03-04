function closeMessage() {
  var msg = document.getElementById("messages");
  msg.parentNode.removeChild(msg);
}

document.addEventListener("DOMContentLoaded", function() {
  document.getElementById("messages").addEventListener("click", closeMessage);
}, true);