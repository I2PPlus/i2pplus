var oldTheme = "ubergine";
var change = false;

function swapStyleSheet(theme) {
    // https://stackoverflow.com/questions/14292997/changing-style-sheet-javascript
    document.getElementById("snarkTheme").setAttribute("href", "/themes/snark/" + theme + "/snark.css");
}

function initThemePreview() {
    var theme = document.getElementById("themeSelect");
    if (theme == null) {
        return;
    }
    oldtheme = theme.value;
    theme.onclick = function() {
        if (change) {
            swapStyleSheet(theme.value);
        } else {
            // skip the first click to avoid the flash
            change = true;
        }
    }
}

function cleanImports() {
    if (document.styleSheets[0].imports) {
        document.styleSheets[0].imports = "";
    }
}

function restoreImports() {
    if (document.styleSheets[0].imports) {
        document.styleSheets[0].imports = document.styleSheets[0].imports;
    }
}

document.addEventListener("DOMContentLoaded", function() {
    cleanImports();
    initThemePreview();
    restoreImports();
}, true);
