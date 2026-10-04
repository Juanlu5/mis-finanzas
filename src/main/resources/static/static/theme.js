// Sets data-theme before the page is painted, so it never flashes in the wrong theme. A file of its own,
// not an inline <script>: the Content-Security-Policy (see SecurityConfig) only runs scripts from files.
(function () {
  try {
    var stored = localStorage.getItem("theme");
    var theme = stored || (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
    document.documentElement.setAttribute("data-theme", theme);
  } catch (e) {}
})();
