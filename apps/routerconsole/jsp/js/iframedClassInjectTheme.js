// inject iframed class and theme name to embedded body tag

function injectClass(f) {
  f.className += ' iframed ';
  var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
  if (!doc.body.classList.contains("iframed")) {
    if (t != null && a != null) {
      doc.documentElement.className += ' ' + t + ' ' + a;
      doc.body.className += ' iframed ' + t + ' ' + a;
    } else {
      doc.body.className += ' iframed';
    }
  }
}

// add empty span to end of page so iframeResizer can reference it
// and we can use it as anchor to scroll to bottom of page for logs

function endOfPage() {
  var end = document.createElement("span");
  end.setAttribute("id", "endOfPage");
  end.setAttribute("data-iframe-height", "");
  frames[a + "_frame"].document.body.appendChild(end);
}

// refresh embedded logs every 30 seconds

function doRefresh() {
  if (u.indexOf(".log") >= 0 && r != null) {
    w = document.getElementById(a + '_frame').contentWindow;
    var end = document.createElement("span");
    end.setAttribute("id", "endOfPage");
    end.setAttribute("data-iframe-height", "");
    frames[a + "_frame"].document.body.appendChild(end);
    if (u.indexOf(".log") >=0) {
      setTimeout("document.getElementById('endOfPage').scrollIntoView({behavior: 'smooth', block: 'end', inline: 'end'});", 3000); // 3 sec delay to ensure dom loaded
    }
  }
}
