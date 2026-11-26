/* LifeLink inline SVG icon system.
 * Creates SVG nodes with the SVG DOM API; no HTML injection, icon fonts, or image assets.
 */
(() => {
  "use strict";
  const NS = "http://www.w3.org/2000/svg";
  const shapes = {
    lifelink: [["path",{d:"M12 21s-7.5-4.6-9-9.2C1.6 7.4 4.1 4 7.4 4c1.9 0 3.5 1.1 4.6 2.7C13.1 5.1 14.7 4 16.6 4 19.9 4 22.4 7.4 21 11.8 19.5 16.4 12 21 12 21Z"}],["path",{d:"M5.5 12h3l1.5-2.5 2.2 5 1.6-2.5h4.7"}]],
    dashboard: [["rect",{x:"3",y:"3",width:"8",height:"8",rx:"1.5"}],["rect",{x:"14",y:"3",width:"7",height:"5",rx:"1.5"}],["rect",{x:"14",y:"11",width:"7",height:"10",rx:"1.5"}],["rect",{x:"3",y:"14",width:"8",height:"7",rx:"1.5"}]],
    users: [["path",{d:"M16 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"}],["circle",{cx:"10",cy:"7",r:"4"}],["path",{d:"M20 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"}]],
    shieldAlert: [["path",{d:"M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z"}],["path",{d:"M12 8v4"}],["path",{d:"M12 16h.01"}]],
    monitor: [["rect",{x:"3",y:"4",width:"18",height:"13",rx:"2"}],["path",{d:"M8 21h8M12 17v4"}]],
    clipboard: [["rect",{x:"5",y:"4",width:"14",height:"17",rx:"2"}],["path",{d:"M9 4.5h6M9 9h6M9 13h6M9 17h3"}]],
    alert: [["path",{d:"m10.3 3.9-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.7-3.1l-8-14a2 2 0 0 0-3.4 0Z"}],["path",{d:"M12 9v4"}],["path",{d:"M12 17h.01"}]],
    refresh: [["path",{d:"M20 7v5h-5M4 17v-5h5"}],["path",{d:"M5.6 9A7 7 0 0 1 17.5 6L20 12M4 12l2.5 6A7 7 0 0 0 18.4 15"}]],
    logout: [["path",{d:"M10 17l5-5-5-5M15 12H3"}],["path",{d:"M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6"}]],
    usersRound: [["path",{d:"M18 21a8 8 0 0 0-16 0"}],["circle",{cx:"10",cy:"8",r:"5"}],["path",{d:"M20 8v6M23 11h-6"}]],
    activity: [["path",{d:"M22 12h-4l-3 9L9 3l-3 9H2"}]],
    inbox: [["path",{d:"M4 4h16l2 11v5H2v-5L4 4Z"}],["path",{d:"M2 15h6l2 3h4l2-3h6"}]],
    shieldCheck: [["path",{d:"M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z"}],["path",{d:"m9 12 2 2 4-4"}]],
    search: [["circle",{cx:"11",cy:"11",r:"7"}],["path",{d:"m20 20-4-4"}]],
    chevronRight: [["path",{d:"m9 18 6-6-6-6"}]],
    chevronLeft: [["path",{d:"m15 18-6-6 6-6"}]],
    clock: [["circle",{cx:"12",cy:"12",r:"9"}],["path",{d:"M12 7v5l3 2"}]],
    check: [["path",{d:"m5 12 4 4L19 6"}]],
    info: [["circle",{cx:"12",cy:"12",r:"9"}],["path",{d:"M12 11v5M12 8h.01"}]],
    chart: [["path",{d:"M3 3v18h18"}],["path",{d:"M18 17V9M13 17V5M8 17v-3"}]],
    key: [["circle",{cx:"8",cy:"15",r:"5"}],["path",{d:"m21 2-9.6 9.6M15.5 7.5l3 3L22 7"}]],
    edit: [["path",{d:"M12 20h9"}],["path",{d:"M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"}]],
    download: [["path",{d:"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}],["path",{d:"m7 10 5 5 5-5M12 15V3"}]],
    filter: [["path",{d:"M4 7h16M7 12h10M10 17h4"}]]
  };
  function create(name, size = 18, className = "") {
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("width", String(size));
    svg.setAttribute("height", String(size));
    svg.setAttribute("fill", "none");
    svg.setAttribute("stroke", "currentColor");
    svg.setAttribute("stroke-width", "1.8");
    svg.setAttribute("stroke-linecap", "round");
    svg.setAttribute("stroke-linejoin", "round");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    if (className) svg.setAttribute("class", className);
    for (const [tag, attrs] of (shapes[name] || shapes.info)) {
      const node = document.createElementNS(NS, tag);
      for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
      svg.appendChild(node);
    }
    return svg;
  }
  function render(root = document) {
    root.querySelectorAll("[data-icon]").forEach((host) => {
      if (host.dataset.iconReady === "true") return;
      const name = host.dataset.icon;
      const size = Number(host.dataset.iconSize || 18);
      host.replaceChildren(create(name, Number.isFinite(size) ? size : 18, "ll-icon-svg"));
      host.dataset.iconReady = "true";
    });
  }
  window.LifeLinkIcons = Object.freeze({ create, render });
})();
