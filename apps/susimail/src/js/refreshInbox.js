var refresh = document.getElementById("serverRefresh");
var inbox = document.getElementById("susimail");
if (inbox && !refresh) {
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/susimail?" + new Date().getTime(), true);
    xhr.responseType = "document";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        var inboxResponse = xhr.responseXML.getElementById("susimail");
        var inboxParent = inbox.parentNode;
        inboxParent.replaceChild(inboxResponse, inbox);
      }
    }
    xhr.send();
  }, 5000);
} else {
  window.location.reload();
}