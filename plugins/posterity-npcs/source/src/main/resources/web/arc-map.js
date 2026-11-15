// ArcEmployees on BlueMap: a "Plan builds here" button that opens the 3D planner
// where you're looking (or on the block you last clicked), for your builders.
(function () {
  "use strict";
  const base = new URL("arc/", document.baseURI).href;   // /map/arc/ next to BlueMap's /map/
  let last = null, el = null, chip = null;

  function where() {
    const app = window.bluemap;
    if (!app || !app.mapViewer || !app.mapViewer.map) return null;
    const id = app.mapViewer.map.data.id;
    if (last && last.map === id && Date.now() - last.at < 120000) return { map: id, x: last.x, y: last.y, z: last.z };
    const p = app.mapViewer.controlsManager.position;
    return { map: id, x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
  }
  function open() {
    const w = where();
    if (!w) return;
    window.open(base + "editor#" + [w.map, w.x, w.y, w.z].join(":"), "_blank", "noopener");
  }
  function show(text) {
    if (!chip) return;
    chip.textContent = text;
    chip.hidden = false;
    clearTimeout(chip._t);
    chip._t = setTimeout(() => { chip.hidden = true; }, 6000);
  }
  async function start() {
    const app = window.bluemap;
    if (!app || !app.events) { setTimeout(start, 500); return; }
    let me = null;
    try {
      const r = await fetch(base + "api/me", { credentials: "same-origin" });
      if (r.ok) me = await r.json();
    } catch (e) { me = null; }
    if (!me) return;   // not signed in through the planner (or BlueMap opened directly on its port)
    el = document.createElement("div");
    el.className = "arc-plan";
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = "Plan builds here";
    btn.title = "Open the 3D planner here, for " + me.builders.map(b => b.name).join(", ");
    btn.onclick = open;
    chip = document.createElement("span");
    chip.className = "arc-chip";
    chip.hidden = true;
    el.appendChild(chip);
    el.appendChild(btn);
    document.body.appendChild(el);
    app.events.addEventListener("bluemapMapInteraction", ev => {
      const hit = ev.detail && (ev.detail.hiresHit || ev.detail.hit);
      if (!hit || !hit.point || !app.mapViewer.map) return;
      const n = hit.face && hit.face.normal ? hit.face.normal : { x: 0, y: 1, z: 0 };
      // The block that was clicked: step back from its surface.
      last = { map: app.mapViewer.map.data.id, x: Math.floor(hit.point.x - n.x * 0.01), y: Math.floor(hit.point.y - n.y * 0.01), z: Math.floor(hit.point.z - n.z * 0.01), at: Date.now() };
      show("Block " + last.x + " " + last.y + " " + last.z + ": the planner opens here");
    });
  }
  start();
})();
