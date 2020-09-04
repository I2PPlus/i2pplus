setInterval(function() {
  var xhrsnark = new XMLHttpRequest();
  var url = location.href;
  xhrsnark.open('GET', url);
  xhrsnark.setRequestHeader("If-Modified-Since", "Sat, 1 Jan 2000 00:00:00 GMT");
  xhrsnark.responseType = "document";
  xhrsnark.onreadystatechange = function () {

    if (xhrsnark.readyState == 4 && xhrsnark.status == 200) {

      var info = document.getElementById("torrentInfoStats");
      var control = document.getElementById("torrentInfoControl");
      var files = document.getElementById("snarkDirInfo");
      var sort = document.getElementById("sortRemaining");

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

      if (files && sort) {
        var filesInner = files.innerHTML;
        var filesResponse = xhrsnark.responseXML.getElementById("snarkDirInfo");
        if (filesResponse) {
          var filesResponseInner = filesResponse.innerHTML;
          var filesParent = files.parentNode;
          if (!Object.is(filesInner, filesResponseInner))
            filesParent.replaceChild(filesResponse, files);
        }
      }

    }

  }
  xhrsnark.send();
}, 10000);
