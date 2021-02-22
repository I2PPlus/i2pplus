var refresh = document.getElementById("serverRefresh");
var mailbox = document.getElementById("mailbox");
if (mailbox && !refresh) {
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/susimail?" + new Date().getTime(), true);
    xhr.responseType = "document";
    var mailboxResponse = xhr.responseXML.getElementById("mailbox");
    var refreshResponse = xhr.responseXML.getElementById("serverRefresh");
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200 &&  (!refresh && refreshResponse != null)) {
        document.body.innerHTML = xhr.body.innerHTML;
      }
    }
    xhr.send();
  }, 5000);
} else {
  window.location.reload();
}