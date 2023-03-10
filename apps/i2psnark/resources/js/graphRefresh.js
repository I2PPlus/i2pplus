var graphcss = document.getElementById("graphcss");
var log = document.getElementById("screenlog");
var noload = document.getElementById("noload");

function initGraphRefresh() {
  var now = new Date().getTime();
  if (log) {
    graphcss.innerText = ":root{--snarkGraph:url('/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&" +
                         "period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&" +
                         "hideTitle=true&hideGrid=true&t=" + now + "')}";
  }
  if (noload) {
    setInterval(initGraphRefresh, 5*60*1000);
  } else {
    setInterval(initGraphRefresh, 15*60*1000);
  }
}

document.addEventListener("DOMContentLoaded", initGraphRefresh);
