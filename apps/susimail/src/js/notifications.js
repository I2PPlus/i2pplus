(function removeNotify() {
  document.addEventListener("DOMContentLoaded", () => {
    const notice = document.getElementById("notify");
    if (notice) {
      if (notice.innerHTML === "") {notice.remove();}
      notice.addEventListener("click", () => {
        notice.remove();
        console.log("Notification nuked!");
      });
    }
  });
})();