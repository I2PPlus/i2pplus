(function removeNotify() {
  document.addEventListener("DOMContentLoaded", () => {
    const notice = document.getElementById("notify");
    if (notice) {
      notice.addEventListener("click", () => {
        notice.remove();
        console.log("Notification nuked!");
      });
    }
  });
})();