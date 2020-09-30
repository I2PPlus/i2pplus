function initGraphRefresh() {

  var now = new Date().getTime();
  var screenlog = document.getElementById("screenlog");
  var graphcss = document.getElementById("graphcss");
  if (screenlog) {
    var graph = "'/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&hideTitle=true&hideGrid=true&t=" + now + "'";
    if (graphcss)
      graphcss.remove();

    var s = document.createElement("style");
    s.type="text/css";
    s.setAttribute("id", "graphcss");
    if (document.body.classList.contains("classic"))
      s.innerHTML = "" +
        ".classic #screenlog, .classic #screenlog.expanded, .classic #screenlog.collapsed {\n" +
        "  background: url('/themes/snark/ubergine/images/hat.png') right -4px bottom -10px no-repeat,\n" +
        "              url(" + graph + ") no-repeat, #ddf;\n" +
        "  background: url('/themes/snark/ubergine/images/hat.png') right -4px bottom -10px no-repeat,\n" +
        "              url(" + graph + ") no-repeat,\n" +
        "              linear-gradient(to bottom, rgba(239, 243, 255, .7), rgba(233, 239, 255, .7));\n" +
        "  background-size: auto 80px, calc(100% + 80px) calc(100% + 20px), 100% 100%;\n" +
        "  background-position: right -4px bottom -10px, left bottom 1px, center center, center top !important;\n" +
        "  background-blend-mode: normal, soft-light, normal !important;\n" +
        "}";

    else if (document.body.classList.contains("dark"))
      s.innerHTML = "" +
        ".dark #screenlog, .dark #screenlog.expanded, .dark #screenlog.collapsed {\n" +
        "  background: url('/themes/snark/ubergine/images/hat.png') no-repeat scroll right bottom,\n" +
        "              url(" + graph + ") no-repeat,\n" +
        "              #000;\n" +
        "  background: url('/themes/snark/ubergine/images/hat.png') no-repeat scroll right bottom,\n" +
        "              repeating-linear-gradient(to bottom, rgba(0,0,0,.7) 2px, rgba(0,32,0,.6) 4px),\n" +
        "              url(" + graph + ") no-repeat,\n" +
        "              linear-gradient(to bottom, #000a00, #000) !important;\n" +
        "  background-size: 60px auto, 100% 100%, calc(100% - 80px) calc(100% - 4px), 100% 100% !important;\n" +
        "  background-position: right bottom, center center, left bottom, center center !important;\n" +
        "  background-blend-mode: screen, darken, normal, normal !important;\n" +
        "}";

    else if (document.body.classList.contains("light"))
      s.innerHTML = "" +
        ".light #screenlog, .light #screenlog.expanded, .light #screenlog.collapsed {\n" +
        "  background: url('/themes/snark/light/images/kitty.png') no-repeat right center,\n" +
        "              repeating-linear-gradient(to bottom, rgba(255,255,255,.5) 2px, rgba(220,220,255,.5) 4px),\n" +
        "              url(" + graph + ") no-repeat,\n" +
        "              linear-gradient(to bottom, #fff, #eef);\n" +
        "  background-size: 60px auto, 100% 100%, calc(100% - 80px) calc(100% - 4px), 100% 100% !important;\n" +
        "  background-position: right bottom, center center, left bottom, center center !important;\n" +
        "  background-blend-mode: multiply, overlay, luminosity, normal !important;\n" +
        "}";

    else if (document.body.classList.contains("midnight"))
      s.innerHTML = "" +
        ".midnight #screenlog, .midnight #screenlog.expanded, .midnight #screenlog.collapsed {\n" +
        "  background: url('/themes/snark/ubergine/images/hat.png') no-repeat scroll right top,\n" +
        "              repeating-linear-gradient(to bottom, rgba(0,0,16,.7) 2px, rgba(0,0,48,.5) 4px),\n" +
        "              url(" + graph + ") no-repeat,\n" +
        "              linear-gradient(to bottom, #001, #000008);\n" +
        "  background-size: 80px auto, 100% 100%, calc(100% - 80px) calc(100% - 8px), 100% 100% !important;\n" +
        "  background-position: right -4px bottom -10px, center center, left bottom, center top !important;\n" +
        "  background-blend-mode: normal, overlay, normal, normal !important;\n" +
        "}";

    else if (document.body.classList.contains("ubergine"))
      s.innerHTML = "" +
        ".ubergine .snarkMessages, .ubergine #screenlog.collapsed, .ubergine #screenlog.xpanded {\n" +
        "  background: url('/themes/snark/ubergine/images/hat.png') no-repeat scroll right center,\n" +
        "              repeating-linear-gradient(to bottom, rgba(16,0,16,.5) 2px, rgba(48,16,48,.6) 4px),\n" +
        "              url(" + graph + ") no-repeat,\n" +
        "              linear-gradient(to bottom, rgba(255,200,255,.1), rgba(255,240,255,.05), rgba(0,0,0,.1))," +
        "              linear-gradient(to bottom, #2a192a, #202) !important;\n" +
        "  background-size: 80px auto, 100% 100%, calc(100% - 80px) calc(100% - 4px), 100% 100%, 100% 100% !important;\n" +
        "  background-position: right -4px bottom -10px, center center, left bottom 2px, center center, center top !important;\n" +
        "  background-blend-mode: lighten, darken, luminosity, normal, normal !important;\n" +
        "}";

    else if (document.body.classList.contains("vanilla"))
      s.innerHTML= "" +
        ".vanilla .snarkMessages, .vanilla #screenlog.collapsed, .vanilla #screenlog.xpanded {\n" +
        "  background: repeating-linear-gradient(to bottom, rgba(77, 69, 62, .4) 2px, rgba(111, 96, 90, .3) 4px),\n" +
        "              url('/themes/snark/vanilla/images/whippy.png') no-repeat scroll right center,\n" +
        "              url(" + graph + ") no-repeat,\n" +
        "              linear-gradient(to bottom, #5f554d, #3f3833);\n" +
        "  background-size: 100% 100%, 60px auto, calc(100% - 80px) 100%, 100% 100% !important;\n" +
        "  background-position: center center, right -4px bottom -10px, left bottom 1px, center center !important;\n" +
        "  background-blend-mode: darken, lighten, luminosity, normal, normal !important;\n" +
        "}";

    document.head.appendChild(s);
  }

}

initGraphRefresh();
setInterval(initGraphRefresh, 15 * 60 * 1000);