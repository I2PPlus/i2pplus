setInterval(function() {
  var xhrsnark = new XMLHttpRequest();
  var url = location.href;
  xhrsnark.open('GET', url);
  xhrsnark.setRequestHeader("If-Modified-Since", "Sat, 1 Jan 2000 00:00:00 GMT");
  xhrsnark.responseType = "document";
  xhrsnark.onreadystatechange = function() {

    if (xhrsnark.readyState == 4 && xhrsnark.status == 200) {

      var info = document.getElementById("torrentInfoStats");
      var control = document.getElementById("torrentInfoControl");
      var files = document.getElementById("snarkDirInfo");
      var remaining = document.getElementById("sortRemaining");
      var complete = document.getElementsByClassName("completed");
      var incomplete = document.getElementsByClassName("incomplete");

      if (info) {
        var infoResponse = xhrsnark.responseXML.getElementById("torrentInfoStats");
        if (infoResponse) {
          var infoParent = info.parentNode;
          if (!Object.is(info.innerHTML, infoResponse.innerHTML))
            infoParent.replaceChild(infoResponse, info);
        }
      }

      if (control) {
        var controlResponse = xhrsnark.responseXML.getElementById("torrentcontrolStats");
        if (controlResponse) {
          var controlParent = control.parentNode;
          if (!Object.is(control.innerHTML, controlResponse.innerHTML))
            controlParent.replaceChild(controlResponse, control);
        }
      }

      if (document.getElementById("toggle_files").checked) {
        if (files && remaining) {
          var updating = document.getElementsByClassName("volatile");
          var updatingResponse = xhrsnark.responseXML.getElementsByClassName("volatile");
          if (updatingResponse) {
            var i;
            for (i = 0; i < updating.length; i++) {
              if (!Object.is(updating[i].innerHTML, updatingResponse[i].innerHTML))
                updating[i].outerHTML = updatingResponse[i].outerHTML;
            }
          }
        }
      }

      if (complete) {
        var completeResponse = xhrsnark.responseXML.getElementsByClassName("completed");
        var j;
        for (j = 0; j < complete.length; j++) {
          if (!Object.is(complete[j].innerHTML, completeResponse[j].innerHTML))
            complete[j].innerHTML = completeResponse[j].innerHTML;
          var priority = complete[j].getElementsByClassName("priority")[0].innerHTML;
          if (priority)
            priority.innerHTML = "";
        }
      }

      if (document.getElementById("setPriority")) { // hide non-functional buttons until we fix folder.js script
        var allHigh = document.getElementById("setallhigh");
        var allNorm = document.getElementById("setallnorm");
        var allSkip = document.getElementById("setallskip");
        if (allHigh)
          allHigh.remove();
        if (allNorm)
          allNorm.remove();
        if (allSkip)
          allSkip.remove();
      }

    }
  }
  xhrsnark.send();
}, 5000);
