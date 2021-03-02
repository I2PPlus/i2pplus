document.getElementById("randomartgen").addEventListener("submit",function() {
  var i2phost = document.getElementById("ra_hostname");
  var input = document.getElementById("ra_input").value;
  if (input != null) {
    i2phost.innerText = input;
  }
});