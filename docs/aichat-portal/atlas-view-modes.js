/*
 * AIchat Code Atlas — optional layout and 3D extension
 *
 * Requires the existing atlas-v2.html and its global data/functions.
 * Load AFTER the existing Atlas inline script.
 *
 * 2D is the default.
 * Extension libraries are fetched only when selected.
 * 3D rendering stops when returning to 2D.
 */

(() => {
  "use strict";

  const $ = id => document.getElementById(id);

  const graph2D = $("graph");
  const layoutSelect = $("layout");
  const fitButton = $("fit");
  const status = $("graphStatus");

  if (!graph2D || !layoutSelect || !fitButton) {
    console.error("AIchat Atlas extension: required controls missing.");
    return;
  }

  if (typeof window.renderGraph !== "function") {
    console.error("AIchat Atlas extension: renderGraph unavailable.");
    return;
  }

  const renderOriginal2D = window.renderGraph;

  const EXTRA_LAYOUTS = {
    dagre: {
      title: "Dagre — směrová hierarchie",
      urls: [
        "https://cdn.jsdelivr.net/npm/cytoscape-dagre@4.0.1/dist/cytoscape-dagre.min.js"
      ],
      global: "cytoscapeDagre"
    },
    fcose: {
      title: "fCoSE — propojené skupiny",
      urls: [
        "https://cdn.jsdelivr.net/npm/layout-base@2.0.1/layout-base.js",
        "https://cdn.jsdelivr.net/npm/cose-base@2.2.0/cose-base.js",
        "https://cdn.jsdelivr.net/npm/cytoscape-fcose@2.2.0/cytoscape-fcose.js"
      ],
      global: "cytoscapeFcose"
    },
    avsdf: {
      title: "AVSDF — přehlednější kruh",
      urls: [
        "https://cdn.jsdelivr.net/npm/cytoscape-avsdf@1.0.0/cytoscape-avsdf.js"
      ],
      global: "cytoscapeAvsdf"
    }
  };

  const THREE_URL =
    "https://cdn.jsdelivr.net/npm/3d-force-graph@1.80.1/dist/3d-force-graph.min.js";
  const SPRITE_URL =
    "https://cdn.jsdelivr.net/npm/three-spritetext@1.10.0/dist/three-spritetext.min.js";

  const loadedScripts = new Map();
  let mode = "2d";
  let graph3D = null;
  let layoutTicket = 0;
  let switching = false;
  let fullscreenBusy = false;
  let lastW = 0;
  let lastH = 0;

  // 3D settings
  let labelMode = "focus"; // "focus" | "all" | "off"
  let layout3DMode = "force"; // "force" | "layers" | "sphere" | "clusters"
  let currentFocusLabels = new Set();

  // Add optional 2D layouts without removing existing options.
  for (const [value, config] of Object.entries(EXTRA_LAYOUTS)) {
    if (layoutSelect.querySelector(`option[value="${value}"]`)) {
      continue;
    }

    const option = document.createElement("option");
    option.value = value;
    option.textContent = config.title;
    layoutSelect.append(option);
  }

  // Preserve user's selected layout from existing localStorage.
  try {
    const preferences =
      JSON.parse(localStorage.getItem("aichat-atlas-v2") || "{}");

    if (preferences.layout &&
        [...layoutSelect.options].some(
          option => option.value === preferences.layout
        )) {
      layoutSelect.value = preferences.layout;
    }

    if (preferences.labelMode3D) {
      labelMode = preferences.labelMode3D;
    }
    if (preferences.layout3D) {
      layout3DMode = preferences.layout3D;
    }
  } catch (_) {
    // Storage is optional.
  }

  // Graph wrapper for unified fullscreen and renderer switching.
  const viewport = document.createElement("div");
  viewport.id = "atlasViewport";

  const host3D = document.createElement("div");
  host3D.id = "atlas3D";
  host3D.setAttribute("aria-label", "Prostorový graf závislostí");

  graph2D.before(viewport);
  viewport.append(graph2D, host3D);

  const exitFullscreen = document.createElement("button");
  exitFullscreen.id = "atlasExitFullscreen";
  exitFullscreen.textContent = "✕ Zavřít fullscreen";
  exitFullscreen.type = "button";
  viewport.append(exitFullscreen);

  const toolbar = fitButton.parentElement;

  const modeButton = document.createElement("button");
  modeButton.id = "atlasMode";
  modeButton.type = "button";
  modeButton.textContent = "◈ Zapnout 3D";
  modeButton.title = "Volitelný prostorový režim";
  toolbar.append(modeButton);

  const fullscreenButton = document.createElement("button");
  fullscreenButton.id = "atlasFullscreen";
  fullscreenButton.type = "button";
  fullscreenButton.textContent = "⛶ Fullscreen";
  toolbar.append(fullscreenButton);

  // 3D Controls container (visible only when 3D is active)
  const controls3D = document.createElement("div");
  controls3D.id = "atlas3DControls";

  const layout3DSelect = document.createElement("select");
  layout3DSelect.id = "atlas3DLayout";
  layout3DSelect.setAttribute("aria-label", "3D rozložení grafu");
  layout3DSelect.innerHTML = `
    <option value="force">3D: Volná síť</option>
    <option value="layers">3D: Vrstvy závislostí (BFS)</option>
    <option value="sphere">3D: Prostorová sféra</option>
    <option value="clusters">3D: Modulové ostrovy</option>
  `;
  layout3DSelect.value = layout3DMode;

  const labelSelect = document.createElement("select");
  labelSelect.id = "atlasLabelMode";
  labelSelect.setAttribute("aria-label", "Zobrazení názvů uzlů ve 3D");
  labelSelect.innerHTML = `
    <option value="focus">Názvy: Okolí výběru</option>
    <option value="all">Názvy: Všechny</option>
    <option value="off">Názvy: Vypnuto (jen tooltip)</option>
  `;
  labelSelect.value = labelMode;

  const focusButton = document.createElement("button");
  focusButton.id = "atlas3DFocus";
  focusButton.type = "button";
  focusButton.textContent = "🎯 Zaměřit vybraný";
  focusButton.title = "Přeletět kamerou na vybraný soubor";

  controls3D.append(layout3DSelect, labelSelect, focusButton);
  toolbar.after(controls3D);

  const style = document.createElement("style");
  style.textContent = `
    #atlasViewport {
      position: relative;
      width: 100%;
      background: #090e18;
    }

    #atlas3D {
      display: none;
      width: 100%;
      height: calc(100dvh - 195px);
      min-height: 440px;
      overflow: hidden;
      border: 1px solid var(--border);
      border-radius: 14px;
      touch-action: none;
    }

    #atlasExitFullscreen {
      display: none;
      position: absolute;
      z-index: 100;
      top: max(12px, env(safe-area-inset-top));
      right: 12px;
      background: #18263aee;
      color: #dceaff;
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 8px 14px;
      font-size: 13px;
      cursor: pointer;
    }

    #atlasExitFullscreen:hover {
      background: #24354dee;
      border-color: var(--blue, #4d8df5);
    }

    #atlasViewport:fullscreen {
      width: 100vw;
      height: 100dvh;
      background: #090e18;
    }

    #atlasViewport:fullscreen #graph,
    #atlasViewport:fullscreen #atlas3D {
      width: 100%;
      height: 100%;
      min-height: 0;
      border: 0;
      border-radius: 0;
    }

    #atlasViewport:fullscreen #atlasExitFullscreen {
      display: block;
    }

    #atlas3DControls {
      display: none;
      align-items: center;
      gap: 8px;
      margin-bottom: 10px;
      flex-wrap: wrap;
    }

    #atlas3DControls select,
    #atlas3DControls button {
      flex: 1;
      min-width: 130px;
      padding: 6px 12px;
      font-size: 12px;
      background: var(--card, #131d2e);
      color: var(--text, #dceaff);
      border: 1px solid var(--border, #24354d);
      border-radius: 8px;
      cursor: pointer;
    }

    #atlas3DControls select:hover,
    #atlas3DControls button:hover {
      border-color: var(--blue, #4d8df5);
    }

    #atlasMode[aria-pressed="true"] {
      border-color: #7de0b7;
      color: #7de0b7;
    }

    @media (max-width: 680px) {
      #atlas3D {
        height: 53dvh;
        min-height: 330px;
      }

      #atlasMode,
      #atlasFullscreen {
        flex-basis: 42%;
      }
    }
  `;
  document.head.append(style);

  function report(message) {
    if (status) status.textContent = message;
  }

  function save3DPreferences() {
    try {
      const preferences =
        JSON.parse(localStorage.getItem("aichat-atlas-v2") || "{}");
      preferences.labelMode3D = labelMode;
      preferences.layout3D = layout3DMode;
      localStorage.setItem("aichat-atlas-v2", JSON.stringify(preferences));
    } catch (_) {
      // Storage is optional
    }
  }

  function loadScript(url) {
    if (loadedScripts.has(url)) return loadedScripts.get(url);

    const promise = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = url;
      script.async = true;

      script.onload = resolve;
      script.onerror = () => {
        script.remove();
        reject(new Error("Nepodařilo se načíst " + url));
      };

      document.head.append(script);
    });

    loadedScripts.set(url, promise);

    promise.catch(() => {
      loadedScripts.delete(url);
    });

    return promise;
  }

  async function ensureLayout(name) {
    const config = EXTRA_LAYOUTS[name];
    if (!config) return;

    if (typeof cytoscape !== "function") {
      throw new Error("Cytoscape.js není dostupný.");
    }

    if (cytoscape("layout", name)) return;

    for (const url of config.urls) {
      await loadScript(url);
    }

    if (!cytoscape("layout", name)) {
      const register = window[config.global];
      if (typeof register === "function") {
        cytoscape.use(register);
      }
    }

    if (!cytoscape("layout", name)) {
      throw new Error("Rozložení " + name + " není registrováno.");
    }
  }

  // Existing Atlas functions call renderGraph() dynamically.
  // Replace only the dispatcher, not the existing 2D implementation.
  window.renderGraph = function renderSelectedMode() {
    if (mode === "3d") {
      renderThreeD();
      return;
    }

    const name = layoutSelect.value;
    const ticket = ++layoutTicket;

    if (!EXTRA_LAYOUTS[name]) {
      renderOriginal2D();
      return;
    }

    report("Načítám rozložení " + name + "…");

    ensureLayout(name).then(() => {
      if (ticket !== layoutTicket ||
          mode !== "2d" ||
          layoutSelect.value !== name) return;

      renderOriginal2D();
    }).catch(error => {
      if (ticket !== layoutTicket || mode !== "2d") return;

      console.warn(error);
      layoutSelect.value = "cose";
      renderOriginal2D();

      report(
        "Rozložení se nenačetlo. Používám původní 2D síť. " +
        error.message
      );
    });
  };

  function getVisibleData() {
    if (!chosen || !data) return null;

    const result = neighborhood();

    // Keep WebGL lightweight and responsive on mobile devices (max 90 nodes).
    const limit = Math.min(Number($("maxNodes").value), 90);
    const ids = [...result.visited].slice(0, limit);
    const visible = new Set(ids);

    const graphNodes = ids.map(path => {
      const item = byPath.get(path);

      return {
        id: path,
        name: item?.name || path.split("/").at(-1),
        color: nodeColors[item?.category] || nodeColors.core,
        val: path === chosen ? 2.5 : 1
      };
    });

    const selectedEdges = edges
      .filter(edge =>
        edgeAllowed(edge) &&
        visible.has(edge.source) &&
        visible.has(edge.target)
      )
      .slice(0, 500);

    const graphLinks = selectedEdges.map(edge => ({
      source: edge.source,
      target: edge.target,
      color: edgeColors[edge.type] || "#879bb9",
      atlasEdge: edge
    }));

    return {
      nodes: graphNodes,
      links: graphLinks,
      limited: result.limitReached || result.visited.size > limit
    };
  }

  function resizeGraphs(force = false) {
    const target = mode === "3d" ? host3D : graph2D;
    const w = Math.max(target.clientWidth, 1);
    const h = Math.max(target.clientHeight, 1);

    if (!force && w === lastW && h === lastH) return;
    lastW = w;
    lastH = h;

    if (mode === "3d" && graph3D) {
      graph3D.width(w).height(h);
    } else if (mode === "2d" && cy) {
      cy.resize();
    }
  }

  function apply3DLayout(nodes, links, layoutName) {
    if (!nodes || nodes.length === 0) return;

    if (layoutName === "force") {
      for (const n of nodes) {
        delete n.fx;
        delete n.fy;
        delete n.fz;
      }
      return;
    }

    if (layoutName === "layers") {
      // BFS shortest-distance hop calculation from chosen node
      const distMap = new Map();
      distMap.set(chosen, 0);
      const queue = [chosen];

      const adj = new Map();
      for (const l of links) {
        const sId = typeof l.source === "object" ? l.source.id : l.source;
        const tId = typeof l.target === "object" ? l.target.id : l.target;
        if (!adj.has(sId)) adj.set(sId, []);
        if (!adj.has(tId)) adj.set(tId, []);
        adj.get(sId).push(tId);
        adj.get(tId).push(sId);
      }

      while (queue.length > 0) {
        const curr = queue.shift();
        const d = distMap.get(curr);
        for (const neighbor of (adj.get(curr) || [])) {
          if (!distMap.has(neighbor)) {
            distMap.set(neighbor, d + 1);
            queue.push(neighbor);
          }
        }
      }

      let maxDepth = 0;
      for (const d of distMap.values()) {
        if (d > maxDepth) maxDepth = d;
      }

      const layers = new Map();
      for (const n of nodes) {
        const d = distMap.has(n.id) ? distMap.get(n.id) : (maxDepth + 1);
        if (!layers.has(d)) layers.set(d, []);
        layers.get(d).push(n);
      }

      for (const [d, layerNodes] of layers.entries()) {
        if (d === 0) {
          for (const n of layerNodes) {
            n.fx = 0;
            n.fy = 0;
            n.fz = 0;
          }
        } else {
          const count = layerNodes.length;
          const radius = Math.max(70, 45 * Math.sqrt(count) + d * 25);
          const y = -d * 85;
          for (let i = 0; i < count; i++) {
            const angle = (2 * Math.PI * i) / count;
            layerNodes[i].fx = radius * Math.cos(angle);
            layerNodes[i].fy = y;
            layerNodes[i].fz = radius * Math.sin(angle);
          }
        }
      }
      return;
    }

    if (layoutName === "sphere") {
      const otherNodes = nodes.filter(n => n.id !== chosen);
      const chosenNode = nodes.find(n => n.id === chosen);

      if (chosenNode) {
        chosenNode.fx = 0;
        chosenNode.fy = 0;
        chosenNode.fz = 0;
      }

      const count = otherNodes.length;
      const radius = Math.max(140, Math.cbrt(nodes.length) * 60);

      for (let i = 0; i < count; i++) {
        const phi = Math.acos(1 - (2 * (i + 0.5)) / Math.max(count, 1));
        const theta = Math.PI * (1 + Math.sqrt(5)) * (i + 0.5);
        otherNodes[i].fx = radius * Math.sin(phi) * Math.cos(theta);
        otherNodes[i].fy = radius * Math.cos(phi);
        otherNodes[i].fz = radius * Math.sin(phi) * Math.sin(theta);
      }
      return;
    }

    if (layoutName === "clusters") {
      const clusters = new Map();
      for (const n of nodes) {
        const item = byPath.get(n.id);
        const cat = item?.category || "core";
        if (!clusters.has(cat)) clusters.set(cat, []);
        clusters.get(cat).push(n);
      }

      const clusterKeys = Array.from(clusters.keys());
      const K = clusterKeys.length;
      const clusterRadius = Math.max(160, K * 35);

      for (let cIdx = 0; cIdx < K; cIdx++) {
        const cat = clusterKeys[cIdx];
        const cNodes = clusters.get(cat);
        const cAngle = (2 * Math.PI * cIdx) / K;
        const cx = clusterRadius * Math.cos(cAngle);
        const cy = 0;
        const cz = clusterRadius * Math.sin(cAngle);

        const cCount = cNodes.length;
        const localRadius = Math.max(40, 22 * Math.sqrt(cCount));

        for (let j = 0; j < cCount; j++) {
          const phi = Math.acos(1 - (2 * (j + 0.5)) / Math.max(cCount, 1));
          const theta = Math.PI * (1 + Math.sqrt(5)) * (j + 0.5);
          cNodes[j].fx = cx + localRadius * Math.sin(phi) * Math.cos(theta);
          cNodes[j].fy = cy + localRadius * Math.cos(phi);
          cNodes[j].fz = cz + localRadius * Math.sin(phi) * Math.sin(theta);
        }
      }
      return;
    }
  }

  function renderThreeD() {
    if (!graph3D || mode !== "3d") return;

    const graph = getVisibleData();
    if (!graph) return;

    // Recalculate focus label candidates (chosen + direct neighbors up to 24)
    currentFocusLabels = new Set([chosen]);
    for (const link of graph.links) {
      const sId = typeof link.source === "object" ? link.source.id : link.source;
      const tId = typeof link.target === "object" ? link.target.id : link.target;
      if (sId === chosen) currentFocusLabels.add(tId);
      if (tId === chosen) currentFocusLabels.add(sId);
      if (currentFocusLabels.size >= 25) break;
    }

    apply3DLayout(graph.nodes, graph.links, layout3DMode);

    graph3D
      .graphData({
        nodes: graph.nodes,
        links: graph.links
      })
      .width(Math.max(host3D.clientWidth, 1))
      .height(Math.max(host3D.clientHeight, 1));

    if (layout3DMode === "force") {
      graph3D.d3ReheatSimulation();
    }

    report(
      "3D · " + graph.nodes.length + " souborů · " +
      graph.links.length + " vazeb" +
      (graph.limited ? " · omezený výběr kvůli výkonu" : "")
    );
  }

  function focusOnChosenNode() {
    if (!graph3D || !chosen) return;
    const gData = graph3D.graphData();
    const node = (gData.nodes || []).find(n => n.id === chosen);
    if (!node) {
      graph3D.zoomToFit(500, 65);
      return;
    }

    const tx = node.x ?? node.fx ?? 0;
    const ty = node.y ?? node.fy ?? 0;
    const tz = node.z ?? node.fz ?? 0;

    const cam = graph3D.cameraPosition();
    let vx = cam.x - tx;
    let vy = cam.y - ty;
    let vz = cam.z - tz;
    let dist = Math.hypot(vx, vy, vz);

    if (dist < 1e-2) {
      vx = 0;
      vy = 30;
      vz = 160;
      dist = Math.hypot(vx, vy, vz);
    }

    const desiredDistance = 160;
    const newPos = {
      x: tx + (vx / dist) * desiredDistance,
      y: ty + (vy / dist) * desiredDistance,
      z: tz + (vz / dist) * desiredDistance
    };

    graph3D.cameraPosition(
      newPos,
      { x: tx, y: ty, z: tz },
      1000
    );
  }

  function supportsWebGL() {
    try {
      const canvas = document.createElement("canvas");
      return !!(
        canvas.getContext("webgl2") ||
        canvas.getContext("webgl")
      );
    } catch (_) {
      return false;
    }
  }

  function escapeText(value) {
    return String(value).replace(/[&<>"']/g, character => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;"
    })[character]);
  }

  function attachWebGLContextListeners() {
    const canvas = host3D.querySelector("canvas");
    if (!canvas) return;

    canvas.addEventListener("webglcontextlost", event => {
      event.preventDefault();
      console.warn("Atlas 3D: WebGL context lost");
      report("WebGL kontext byl ztracen (paměť GPU). Obnovuji...");
    }, false);

    canvas.addEventListener("webglcontextrestored", () => {
      console.log("Atlas 3D: WebGL context restored");
      report("WebGL kontext obnoven.");
      if (mode === "3d") {
        renderThreeD();
      }
    }, false);
  }

  async function enableThreeD() {
    if (switching) return;
    switching = true;
    modeButton.disabled = true;

    try {
      if (!supportsWebGL()) {
        throw new Error("Na tomto zařízení není dostupné WebGL.");
      }

      report("Připravuji prostorový renderer…");

      await loadScript(THREE_URL);

      if (typeof window.ForceGraph3D !== "function") {
        throw new Error("3D knihovna se nenačetla správně.");
      }

      try {
        await loadScript(SPRITE_URL);
      } catch (error) {
        console.warn("3D textové popisky nejsou dostupné:", error);
      }

      mode = "3d";
      ++layoutTicket;

      graph2D.style.display = "none";
      host3D.style.display = "block";
      controls3D.style.display = "flex";

      if (cy) {
        cy.destroy();
        cy = null;
      }

      graph3D = new window.ForceGraph3D(host3D, {
        controlType: "trackball",
        rendererConfig: {
          antialias: false,
          alpha: false,
          preserveDrawingBuffer: true,
          powerPreference: "high-performance"
        }
      });

      graph3D
        .backgroundColor("#090e18")
        .nodeRelSize(5)
        .nodeResolution(6)
        .nodeColor(node => (node.id === chosen ? "#ffffff" : node.color))
        .nodeLabel(node => escapeText(node.name))
        .nodeThreeObjectExtend(true)
        .nodeThreeObject(node => {
          if (!window.SpriteText || labelMode === "off") {
            return null;
          }

          if (labelMode === "focus" && !currentFocusLabels.has(node.id)) {
            return null;
          }

          const isChosen = node.id === chosen;
          const sprite = new window.SpriteText(node.name);
          sprite.color = isChosen ? "#ffffff" : "#dceaff";
          sprite.textHeight = isChosen ? 5.5 : 3.6;
          sprite.backgroundColor = isChosen
            ? "rgba(16, 27, 43, 0.92)"
            : "rgba(10, 16, 26, 0.80)";
          sprite.borderColor = isChosen
            ? "#7de0b7"
            : "rgba(80, 120, 180, 0.4)";
          sprite.borderWidth = isChosen ? 0.8 : 0.4;
          sprite.borderRadius = 3;
          sprite.padding = 2;

          if (sprite.material) {
            sprite.material.depthWrite = false;
          }

          sprite.position.set(0, isChosen ? 8 : 6, 0);
          return sprite;
        })
        .linkColor(link => {
          const sId = typeof link.source === "object" ? link.source.id : link.source;
          const tId = typeof link.target === "object" ? link.target.id : link.target;
          if (sId === chosen) return "#4deeea"; // odchozí vazba (co vybraný soubor používá)
          if (tId === chosen) return "#f7b731"; // příchozí vazba (kdo používá vybraný soubor)
          return link.color || "#879bb9";
        })
        .linkOpacity(0.35)
        .linkWidth(link => {
          const sId = typeof link.source === "object" ? link.source.id : link.source;
          const tId = typeof link.target === "object" ? link.target.id : link.target;
          return (sId === chosen || tId === chosen) ? 1.6 : 0.6;
        })
        .linkDirectionalArrowLength(link => {
          const sId = typeof link.source === "object" ? link.source.id : link.source;
          const tId = typeof link.target === "object" ? link.target.id : link.target;
          return (sId === chosen || tId === chosen) ? 4.5 : 2.5;
        })
        .linkDirectionalArrowRelPos(1)
        .linkDirectionalParticles(link => {
          const sId = typeof link.source === "object" ? link.source.id : link.source;
          const tId = typeof link.target === "object" ? link.target.id : link.target;
          return (sId === chosen || tId === chosen) ? 2 : 0;
        })
        .linkDirectionalParticleSpeed(0.005)
        .linkDirectionalParticleWidth(1.6)
        .enableNodeDrag(false)
        .cooldownTicks(120)
        .onNodeClick(node => choose(node.id))
        .onLinkClick(link => {
          if (link.atlasEdge) explain(link.atlasEdge);
        });

      attachWebGLContextListeners();
      renderThreeD();

      modeButton.textContent = "↩ Vrátit 2D";
      modeButton.setAttribute("aria-pressed", "true");

    } catch (error) {
      console.error(error);

      mode = "2d";
      graph2D.style.display = "";
      host3D.style.display = "none";
      controls3D.style.display = "none";

      if (graph3D) {
        graph3D.pauseAnimation();
      }

      window.renderGraph();
      report("3D režim není dostupný: " + error.message);

    } finally {
      modeButton.disabled = false;
      switching = false;
    }
  }

  function disableThreeD() {
    if (switching) return;

    mode = "2d";

    if (graph3D) {
      try {
        graph3D.pauseAnimation();
        graph3D.controls()?.dispose?.();
        graph3D.renderer()?.dispose?.();
        graph3D.renderer()?.forceContextLoss?.();
      } catch (error) {
        console.warn("3D cleanup:", error);
      }

      graph3D = null;
    }

    host3D.replaceChildren();
    host3D.style.display = "none";
    controls3D.style.display = "none";
    graph2D.style.display = "";

    modeButton.textContent = "◈ Zapnout 3D";
    modeButton.setAttribute("aria-pressed", "false");

    window.renderGraph();
  }

  modeButton.setAttribute("aria-pressed", "false");

  modeButton.onclick = () => {
    if (mode === "3d") disableThreeD();
    else enableThreeD();
  };

  function zoom3D(factor) {
    if (!graph3D) return;
    const cam = graph3D.cameraPosition();
    const target = { x: 0, y: 0, z: 0 };
    if (chosen) {
      const gData = graph3D.graphData();
      const node = (gData.nodes || []).find(n => n.id === chosen);
      if (node) {
        target.x = node.x ?? node.fx ?? 0;
        target.y = node.y ?? node.fy ?? 0;
        target.z = node.z ?? node.fz ?? 0;
      }
    }
    const vx = cam.x - target.x;
    const vy = cam.y - target.y;
    const vz = cam.z - target.z;
    const currentDist = Math.hypot(vx, vy, vz) || 1;
    const newDist = Math.max(30, Math.min(currentDist * factor, 3000));
    const ratio = newDist / currentDist;

    graph3D.cameraPosition(
      {
        x: target.x + vx * ratio,
        y: target.y + vy * ratio,
        z: target.z + vz * ratio
      },
      target,
      250
    );
  }

  function savePng3D() {
    if (!graph3D) return;
    const canvas = host3D.querySelector("canvas");
    if (!canvas) return;

    try {
      const dataUrl = canvas.toDataURL("image/png");
      const link = document.createElement("a");
      link.href = dataUrl;
      link.download = "aichat-code-atlas-3d.png";
      link.click();
      report("3D snímek grafu byl stažen.");
    } catch (err) {
      report("Export 3D PNG se nezdařil: " + err.message);
    }
  }

  fitButton.addEventListener("click", () => {
    if (mode === "3d" && graph3D) {
      graph3D.zoomToFit(500, 65);
    }
  });

  $("zoomIn")?.addEventListener("click", () => {
    if (mode === "3d") zoom3D(0.75);
  });

  $("zoomOut")?.addEventListener("click", () => {
    if (mode === "3d") zoom3D(1.33);
  });

  $("savePng")?.addEventListener("click", () => {
    if (mode === "3d") savePng3D();
  });

  focusButton.addEventListener("click", () => {
    if (mode === "3d" && graph3D) {
      focusOnChosenNode();
    }
  });

  layout3DSelect.addEventListener("change", () => {
    layout3DMode = layout3DSelect.value;
    save3DPreferences();
    if (mode === "3d" && graph3D) {
      const gData = graph3D.graphData();
      apply3DLayout(gData.nodes, gData.links, layout3DMode);
      graph3D.graphData(gData);
      if (layout3DMode === "force") {
        graph3D.d3ReheatSimulation();
      }
    }
  });

  labelSelect.addEventListener("change", () => {
    labelMode = labelSelect.value;
    save3DPreferences();
    if (mode === "3d" && graph3D) {
      graph3D.nodeThreeObject(graph3D.nodeThreeObject());
    }
  });

  function updateFullscreenButtons() {
    const isFs = Boolean(document.fullscreenElement);
    fullscreenButton.textContent = isFs ? "✕ Zmenšit" : "⛶ Fullscreen";
    fullscreenButton.setAttribute("aria-pressed", isFs ? "true" : "false");
    exitFullscreen.style.display = isFs ? "block" : "none";
  }

  async function toggleAtlasFullscreen() {
    if (fullscreenBusy) return;
    fullscreenBusy = true;

    try {
      if (!document.fullscreenElement) {
        if (viewport.requestFullscreen) {
          await viewport.requestFullscreen();
        } else if (viewport.webkitRequestFullscreen) {
          await viewport.webkitRequestFullscreen();
        } else {
          throw new Error("Fullscreen API není podporováno.");
        }
      } else {
        if (document.exitFullscreen) {
          await document.exitFullscreen();
        } else if (document.webkitExitFullscreen) {
          await document.webkitExitFullscreen();
        }
      }
    } catch (error) {
      report("Fullscreen není dostupný: " + error.message);
    } finally {
      fullscreenBusy = false;
      updateFullscreenButtons();
    }
  }

  fullscreenButton.onclick = toggleAtlasFullscreen;

  exitFullscreen.onclick = () => {
    if (document.fullscreenElement) {
      toggleAtlasFullscreen();
    }
  };

  function onFullscreenChanged() {
    updateFullscreenButtons();
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        resizeGraphs(true);
        if (mode === "2d" && cy) {
          cy.fit(undefined, 40);
        }
      });
    });
  }

  document.addEventListener("fullscreenchange", onFullscreenChanged);
  document.addEventListener("webkitfullscreenchange", onFullscreenChanged);

  if (typeof ResizeObserver !== "undefined") {
    new ResizeObserver(() => resizeGraphs()).observe(viewport);
  } else {
    window.addEventListener("resize", () => resizeGraphs());
  }

  // Save battery and GPU cycles when switching browser tabs.
  document.addEventListener("visibilitychange", () => {
    if (mode !== "3d" || !graph3D) return;

    if (document.hidden) graph3D.pauseAnimation();
    else graph3D.resumeAnimation();
  });

  // Default mode is always 2D, regardless of previous 3D usage.
})();
