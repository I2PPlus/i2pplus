// get dimensions of embedded video
// TODO: fixup getMetadata() to work with ajax refresh

var vid = document.getElementById("embedVideo");
var dirlist = document.getElementById("dirlist");
var vids = document.querySelectorAll("video");

function getDimensions() {
  var title = document.getElementById("videoTitle");
  vid.addEventListener("loadedmetadata", function() {
    title.insertAdjacentHTML('beforeend', "&nbsp;[" + vid.videoWidth + " x " + vid.videoHeight + "]");
  });
}

function getMetadata() {
  var filenames = document.getElementsByTagName("snarkFileName");
  var metadata = document.getElementById("metadata");
  vids.forEach((elem) => {
    var insert = elem.parentNode.nextSibling;
    elem.addEventListener("loadedmetadata", function() {
      if (insert != null)
        insert.insertAdjacentHTML('beforeend', "<br><span id='metadata'>[" + elem.videoWidth + " x " + elem.videoHeight + "]<span>");
    });
  });
}

if (vid) {
  getDimensions();
}