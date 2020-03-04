function toggleInfo() {
    var x = document.getElementsByClassName("tunnelInfo");
    var btn = document.getElementById("toggleInfo");
    var i;
    for (i = 0; i < x.length; i++) {
        if (x[i].style.display === "none") {
            x[i].style.display = "table-row";
            btn.innerHTML = "<img src='/themes/console/dark/images/sort_up.png' title='Hide Tunnel Info'>";
        } else {
            x[i].style.display = "none";
            btn.innerHTML = "<img src='/themes/console/dark/images/sort_down.png' title='Show Tunnel Info'>";
        }
    }
}