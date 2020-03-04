// setup torrent display buttons so we can show/hide snarks based on status

var allOdd = document.getElementsByClassName("snarkTorrentOdd");
var allEven = document.getElementsByClassName("snarkTorrentEven");
var active = document.getElementsByClassName("active");
var inactive = document.getElementsByClassName("inactive");
var downloading = document.getElementsByClassName("downloading");
var seeding = document.getElementsByClassName("seeding");
var complete = document.getElementsByClassName("complete");
var incomplete = document.getElementsByClassName("incomplete");
var peerinfo = document.getElementsByClassName("peerinfo");
var debuginfo = document.getElementsByClassName("debuginfo");
var i = 0;

function showAll() {
  for (i = 0; i < allOdd.length; i++) {
    allOdd[i].style.display = "table-row";
  }
  for (i = 0; i < allEven.length; i++) {
    allEven[i].style.display = "table-row";
  }
}

function showActive() {
  for (i = 0; i < allOdd.length; i++) {
    allOdd[i].style.display = "none";
  }
  for (i = 0; i < allEven.length; i++) {
    allEven[i].style.display = "none";
  }
  for (i = 0; i < active.length; i++) {
    active[i].style.display = "table-row";
  }
  for (i = 0; i < peerinfo.length; i++) {
    peerinfo[i].style.display = "table-row";
  }
  for (i = 0; i < debuginfo.length; i++) {
    debuginfo[i].style.display = "table-row";
  }
}

function showInactive() {
  for (i = 0; i < allOdd.length; i++) {
    allOdd[i].style.display = "none";
  }
  for (i = 0; i < allEven.length; i++) {
    allEven[i].style.display = "none";
  }
  for (i = 0; i < inactive.length; i++) {
    inactive[i].style.display = "table-row";
  }
}

function showDownloading() {
  for (i = 0; i < allOdd.length; i++) {
    allOdd[i].style.display = "none";
  }
  for (i = 0; i < allEven.length; i++) {
    allEven[i].style.display = "none";
  }
  for (i = 0; i < downloading.length; i++) {
    downloading[i].style.display = "table-row";
  }
  for (i = 0; i < peerinfo.length; i++) {
    peerinfo[i].style.display = "table-row";
  }
  for (i = 0; i < debuginfo.length; i++) {
    debuginfo[i].style.display = "table-row";
  }
}

function showSeeding() {
  for (i = 0; i < allOdd.length; i++) {
    allOdd[i].style.display = "none";
  }
  for (i = 0; i < allEven.length; i++) {
    allEven[i].style.display = "none";
  }
  for (i = 0; i < seeding.length; i++) {
    seeding[i].style.display = "table-row";
  }
  for (i = 0; i < peerinfo.length; i++) {
    peerinfo[i].style.display = "table-row";
  }
  for (i = 0; i < debuginfo.length; i++) {
    debuginfo[i].style.display = "table-row";
  }
}

function showComplete() {
  for (i = 0; i < allOdd.length; i++) {
    allOdd[i].style.display = "none";
  }
  for (i = 0; i < allEven.length; i++) {
    allEven[i].style.display = "none";
  }
  for (i = 0; i < seeding.length; i++) {
    seeding[i].style.display = "table-row";
  }
  for (i = 0; i < complete.length; i++) {
    complete[i].style.display = "table-row";
  }
  for (i = 0; i < peerinfo.length; i++) {
    peerinfo[i].style.display = "table-row";
  }
  for (i = 0; i < debuginfo.length; i++) {
    debuginfo[i].style.display = "table-row";
  }
}

function showIncomplete() {
  for (i = 0; i < allOdd.length; i++) {
    allOdd[i].style.display = "none";
  }
  for (i = 0; i < allEven.length; i++) {
    allEven[i].style.display = "none";
  }
  for (i = 0; i < incomplete.length; i++) {
    incomplete[i].style.display = "table-row";
  }
  for (i = 0; i < downloading.length; i++) {
    downloading[i].style.display = "table-row";
  }
  for (i = 0; i < peerinfo.length; i++) {
    peerinfo[i].style.display = "table-row";
  }
  for (i = 0; i < debuginfo.length; i++) {
    debuginfo[i].style.display = "table-row";
  }
}
