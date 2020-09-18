function initGraphRefresh() {

  var now = new Date().getTime();
  var screenlog = document.getElementById("screenlog");
  var graphcss = document.getElementById("graphcss");
  var noload = document.getElementById("noload");
  if (screenlog) {
    var graph = "'/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&hideTitle=true&hideGrid=true&t=" + now + "'";
    if (graphcss)
      graphcss.remove();

    var s = document.createElement("style");
    s.type="text/css";
    s.setAttribute("id", "graphcss");
    if (document.body.classList.contains("classic"))
      s.innerHTML = "" +
        ".classic #screenlog, .classic #screenlog.expanded, .classic #screenlog.collapsed {" +
        "  background: url('/themes/snark/ubergine/images/hat.png') right -4px bottom -10px no-repeat," +
        "              url(" + graph + ") no-repeat, #ddf;" +
        "  background: url('/themes/snark/ubergine/images/hat.png') right -4px bottom -10px no-repeat," +
        "              url(" + graph + ") no-repeat," +
        "              linear-gradient(to bottom, rgba(239, 243, 255, .7), rgba(233, 239, 255, .7));" +
        "  background-size: auto 80px, calc(100% + 80px) calc(100% + 20px), 100% 100%;" +
        "  background-position: right -4px bottom -10px, left bottom 1px, center center, center top !important;" +
        "  background-blend-mode: normal, soft-light, normal !important;" +
        "}";

    else if (document.body.classList.contains("dark"))
      s.innerHTML = "" +
        ".dark #screenlog, .dark #screenlog.expanded, .dark #screenlog.collapsed {" +
        "  background: url('/themes/snark/ubergine/images/hat.png') no-repeat scroll right bottom," +
        "              url(" + graph + ") no-repeat," +
        "              #000;" +
        "  background: url('/themes/snark/ubergine/images/hat.png') no-repeat scroll right bottom," +
        "              repeating-linear-gradient(to bottom, rgba(0,0,0,.7) 2px, rgba(0,32,0,.6) 4px)," +
        "              url(" + graph + ") no-repeat," +
        "              linear-gradient(to bottom, #000a00, #000) !important;" +
        "  background-size: 60px auto, 100% 100%, calc(100% - 80px) calc(100% - 4px), 100% 100% !important;" +
        "  background-position: right bottom, center center, left bottom, center center !important;" +
        "  background-blend-mode: screen, darken, normal, normal !important;" +
        "}";

    else if (document.body.classList.contains("light"))
      s.innerHTML = "" +
        ".light #screenlog, .light #screenlog.expanded, .light #screenlog.collapsed {" +
        "  background: url(/themes/snark/images/kitty.png) no-repeat right center," +
        "              repeating-linear-gradient(to bottom, rgba(255,255,255,.5) 2px, rgba(220,220,255,.5) 4px)," +
        "              url(" + graph + ") no-repeat," +
        "              linear-gradient(to bottom, #fff, #eef);" +
        "  background-size: 60px auto, 100% 100%, calc(100% - 80px) calc(100% - 4px), 100% 100% !important;" +
        "  background-position: right bottom, center center, left bottom, center center !important;" +
        "  background-blend-mode: multiply, overlay, luminosity, normal !important;" +
        "}";

    else if (document.body.classList.contains("midnight"))
      s.innerHTML = "" +
        ".midnight #screenlog, .midnight #screenlog.expanded, .midnight #screenlog.collapsed {" +
        "  background: url('/themes/snark/ubergine/images/hat.png') no-repeat scroll right top," +
        "              repeating-linear-gradient(to bottom, rgba(0,0,16,.7) 2px, rgba(0,0,48,.5) 4px)," +
        "              url(" + graph + ") no-repeat," +
        "              linear-gradient(to bottom, #001, #000008);" +
        "  background-size: 80px auto, 100% 100%, calc(100% - 80px) calc(100% - 8px), 100% 100% !important;" +
        "  background-position: right -4px bottom -10px, center center, left bottom, center top !important;" +
        "  background-blend-mode: normal, overlay, normal, normal !important;" +
        "}";

    else if (document.body.classList.contains("ubergine"))
      s.innerHTML = "" +
        ".ubergine .snarkMessages, .ubergine #screenlog.collapsed, .ubergine #screenlog.xpanded {" +
        "  background: url(/themes/snark/ubergine/images/hat.png) no-repeat scroll right center," +
        "              repeating-linear-gradient(to bottom, rgba(16,0,16,.5) 2px, rgba(48,16,48,.6) 4px)," +
        "              url(" + graph + ") no-repeat," +
        "              linear-gradient(to bottom, rgba(255,200,255,.1), rgba(255,240,255,.05), rgba(0,0,0,.1))," +
        "              linear-gradient(to bottom, #2a192a, #202) !important;" +
        "  background-size: 80px auto, 100% 100%, calc(100% - 80px) calc(100% - 4px), 100% 100%, 100% 100% !important;" +
        "  background-position: right -4px bottom -10px, center center, left bottom 2px, center center, center top !important;" +
        "  background-blend-mode: lighten, darken, luminosity, normal, normal !important;" +
        "}";

    else if (document.body.classList.contains("vanilla"))
      s.innerHTML= "" +
        ".vanilla .snarkMessages, .vanilla #screenlog.collapsed, .vanilla #screenlog.xpanded {" +
        "  background: repeating-linear-gradient(to bottom, rgba(77, 69, 62, .4) 2px, rgba(111, 96, 90, .3) 4px)," +
        "              url(/themes/snark/images/whippy.png) no-repeat scroll right center," +
        "              url(" + graph + ") no-repeat," +
        "              linear-gradient(to bottom, #5f554d, #3f3833);" +
        "  background-size: 100% 100%, 60px auto, calc(100% - 80px) 100%, 100% 100% !important;" +
        "  background-position: center center, right -4px bottom -10px, left bottom 1px, center center !important;" +
        "  background-blend-mode: darken, lighten, luminosity, normal, normal !important;" +
        "}";

    document.head.appendChild(s);
  }

}

initGraphRefresh();

if (noload)
  setInterval(initGraphRefresh, 4 * 60 * 1000);
else
  setInterval(initGraphRefresh, 30 * 60 * 1000);
