toggle.onclick = function() {
    var x = document.getElementById("password");
    var toggle = document.getElementById("toggle");
    var hide = "Hide password";
    var show = "Show password";
    if (toggle.innerHTML === show) {
        toggle.innerHTML = hide;
        x.type="text";
        toggle.classList.add("hidepass");
        toggle.classList.remove("showpass");
    } else {
        toggle.innerHTML = show;
        x.type="password";
        toggle.classList.remove("hidepass");
        toggle.classList.add("showpass");
   }
}
