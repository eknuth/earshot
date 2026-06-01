/* Earshot site behaviour. Progressive enhancement only:
   the page is fully readable, and visually complete, with JS disabled. */
(function () {
  "use strict";

  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---- Page-load reveal: drop the preload gate on the next frame ---- */
  requestAnimationFrame(function () {
    requestAnimationFrame(function () {
      document.body.classList.remove("preload");
    });
  });

  /* ---- Syntax highlighting ---- */
  if (window.hljs) {
    document.querySelectorAll("pre code").forEach(function (block) {
      window.hljs.highlightElement(block);
    });
  }

  /* ---- Hero waveform: build a believable amplitude envelope ----
     A handful of overlapping sine components gives the bars a natural,
     non-repeating-looking shape instead of a flat row of equal bars. */
  var wave = document.querySelector("[data-wave]");
  if (wave) {
    var COUNT = 48;
    var frag = document.createDocumentFragment();
    for (var i = 0; i < COUNT; i++) {
      var t = i / COUNT;
      // Layered sines -> organic envelope, clamped to a readable range.
      var env =
        0.55 +
        0.30 * Math.sin(t * Math.PI * 6.0 + 0.6) +
        0.16 * Math.sin(t * Math.PI * 13.0 + 1.7) +
        0.10 * Math.sin(t * Math.PI * 23.0);
      var amp = Math.max(0.16, Math.min(1, env));
      var bar = document.createElement("span");
      bar.className = "bar";
      bar.style.setProperty("--amp", amp.toFixed(3));
      bar.style.setProperty("--dur", (1.3 + (i % 5) * 0.16).toFixed(2) + "s");
      bar.style.setProperty("--delay", (-(i % 7) * 0.21).toFixed(2) + "s");
      frag.appendChild(bar);
    }
    wave.appendChild(frag);
  }

  /* ---- Pause the waveform animation while the device panel is offscreen ---- */
  var device = document.querySelector(".device");
  if (device && !reduce && "IntersectionObserver" in window) {
    var paused = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        device.classList.toggle("paused", !e.isIntersecting);
      });
    }, { threshold: 0 });
    paused.observe(device);
  }

  /* ---- Quickstart tabs ---- */
  var tabs = Array.prototype.slice.call(document.querySelectorAll('[role="tab"]'));
  function selectTab(tab) {
    tabs.forEach(function (t) {
      var selected = t === tab;
      t.setAttribute("aria-selected", String(selected));
      t.tabIndex = selected ? 0 : -1;
      var panel = document.getElementById(t.getAttribute("aria-controls"));
      if (panel) panel.hidden = !selected;
    });
  }
  tabs.forEach(function (tab, i) {
    tab.tabIndex = tab.getAttribute("aria-selected") === "true" ? 0 : -1;
    tab.addEventListener("click", function () { selectTab(tab); });
    tab.addEventListener("keydown", function (e) {
      var dir = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      if (!dir) return;
      e.preventDefault();
      var next = tabs[(i + dir + tabs.length) % tabs.length];
      next.focus();
      selectTab(next);
    });
  });

  /* ---- Copy buttons ---- */
  document.querySelectorAll("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var pre = btn.closest(".codeblock").querySelector("pre code");
      if (!pre) return;
      var text = pre.innerText;
      var label = btn.querySelector(".copy-label");
      var live = btn.closest(".codeblock").querySelector("[data-copy-status]");
      var done = function () {
        btn.classList.add("copied");
        if (label) label.textContent = "Copied";
        if (live) live.textContent = "Code copied to clipboard";
        setTimeout(function () {
          btn.classList.remove("copied");
          if (label) label.textContent = "Copy";
          if (live) live.textContent = "";
        }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done).catch(function () {});
      } else {
        var ta = document.createElement("textarea");
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand("copy"); done(); } catch (e) {}
        document.body.removeChild(ta);
      }
    });
  });

  /* ---- Scroll reveals + chart fill (respect reduced motion) ---- */
  function fillBars() {
    var maxFill = 0;
    document.querySelectorAll(".bar-fill").forEach(function (b) {
      maxFill = Math.max(maxFill, parseFloat(b.getAttribute("data-fill")) || 0);
    });
    document.querySelectorAll(".bar-fill").forEach(function (b) {
      var v = parseFloat(b.getAttribute("data-fill")) || 0;
      var scale = maxFill ? v / maxFill : 0;
      b.style.transform = "scaleX(" + scale.toFixed(3) + ")";
    });
  }

  if (reduce || !("IntersectionObserver" in window)) {
    document.querySelectorAll(".reveal").forEach(function (el) { el.classList.add("in"); });
    fillBars();
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("in");
        if (entry.target.querySelector && entry.target.querySelector(".bar-fill")) {
          fillBars();
        }
        io.unobserve(entry.target);
      });
    }, { threshold: 0.16, rootMargin: "0px 0px -8% 0px" });
    document.querySelectorAll(".reveal").forEach(function (el) { io.observe(el); });
  }

  /* ---- Active-section nav highlight (scroll spy) ---- */
  var navLinks = Array.prototype.slice.call(
    document.querySelectorAll('.nav-links a[href^="#"]')
  ).filter(function (a) { return !a.classList.contains("nav-cta"); });
  var sections = navLinks
    .map(function (a) { return document.querySelector(a.getAttribute("href")); })
    .filter(Boolean);

  if (sections.length && "IntersectionObserver" in window) {
    var byId = {};
    navLinks.forEach(function (a) { byId[a.getAttribute("href").slice(1)] = a; });
    var spy = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        navLinks.forEach(function (a) {
          a.removeAttribute("aria-current");
          a.classList.remove("active");
        });
        var link = byId[entry.target.id];
        if (link) {
          link.setAttribute("aria-current", "true");
          link.classList.add("active");
        }
      });
    }, { rootMargin: "-45% 0px -50% 0px", threshold: 0 });
    sections.forEach(function (s) { spy.observe(s); });
  }
})();
