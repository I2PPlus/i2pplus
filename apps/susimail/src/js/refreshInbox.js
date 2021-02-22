import {removeNotify} from "/susimail/js/notifications.js";

var mailbox = document.getElementById("mailbox");
var main = document.getElementById("main");
var serverRefresh = document.getElementById("serverRefresh");
var notify = document.getElementById("notify");
var autorefresh = document.getElementById("autorefresh");

var interval = setInterval(function() {
  if (mailbox && !serverRefresh) {
    var xhrmail = new XMLHttpRequest();
    xhrmail.open("GET", "/susimail?" + new Date().getTime(), true);
    xhrmail.responseType = "document";
    xhrmail.onreadystatechange = function() {
      if (xhrmail.readyState==4 && xhrmail.status==200 && main && serverRefresh) {
        var mailboxResponse = xhrmail.responseXML.getElementById("mailbox");
        main.innerHTML = xhrmail.responseXML.getElementById("main").innerHTML;
      }
    }
    removeNotify();
    xhrmail.send();
  } else if (notify) {
    setTimeout(clearNotify, 10000);
  }
}, 5000);

removeNotify();

function clearNotify() {
  clearInterval(interval);
  notify.style.display = "none";
  notify.remove();
  autorefresh.remove();
  window.location.reload(true);
}