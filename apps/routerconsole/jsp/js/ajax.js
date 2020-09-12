var fails = 0;

function ajax(url, target, refresh) {
  if (window.XMLHttpRequest) {
    request = new XMLHttpRequest();
    request.onreadystatechange = function() {ajaxDone(url, target, refresh);};
    request.open("GET", url, true);
    request.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
    request.setRequestHeader('Accept', 'text/html');
    request.setRequestHeader('Cache-Control', 'no-store');
    request.send(null);
  }
}

function ajaxDone(url, target, refresh) {
  if (request.readyState == 4) {
    if (request.status == 200) {
      fails = 0;
      results = request.responseText;
      document.getElementById(target).innerHTML = results;
      //document.getElementsbyClassName("hideifdown").style.display="block";
    } else if (fails == 0) {
      // avoid spurious message if cancelled by user action
      fails++;
    } else {
      document.getElementById(target).innerHTML = failMessage;
      //document.getElementByClassName("hideifdown").style.display="none";
    }
    setTimeout(function() {ajax(url, target, refresh);}, refresh);
  }
}