const validateNewTorrentForm=(evt)=>{
  var regexStr = document.getElementById('regexStr').value;
  try { new RegExp(regexStr); return true; }
  catch(err) { alert(err.message); evt.preventDefault(); }
}

document.addEventListener("DOMContentLoaded", function() {
  document.getElementById('regexFilters').addEventListener("submit", validateNewTorrentForm);
}, true);
