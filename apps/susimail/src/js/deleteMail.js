/* I2P+ deleteMail.js for Susimail by dr|z3d */
/* Ensures delete mail modal is visible on instantiation */

(function handleModal() {
  const toggleModalStyles = () => {
    const iframed = document.documentElement.classList.contains("iframed") || window.top != window.parent.top;
    const modal = document.getElementById("nukemail");
    if (modal) {
      document.body.classList.add("modal");
      window.scrollTo(0, 0);
      document.body.style.overflow = "hidden";
      if (iframed) {
        window.parent.scrollTo(0,0);
        window.parent.document.body.style.overflow = "hidden";
      }
    } else {
      document.body.classList.remove("modal");
      document.body.style.overflow = "";
      if (iframed) {window.parent.document.body.style.overflow = "";}
    }
  };
  document.addEventListener("DOMContentLoaded", toggleModalStyles);
})();