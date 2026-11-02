
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

  const loadedScripts = new Map();
  let mode = "2d";
  let graph3D = null;
  let layoutTicket = 0;
  let switching = false;

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
  } catch (_) {
    // Storage is optional.
  }

  // New graph wrapper for fullscreen and renderer switching.
  const viewport = document.createElement("div");
  viewport.id = "atlasViewport";

  const host3D = document.createElement("div");
  host3D.id = "atlas3D";
  host3D.setAttribute("aria-label", "Prostorový graf závislostí");

  graph2D.before(viewport);
  viewport.append(graph2D, host3D);

  const exitFullscreen = document.createElement("button");
  exitFullscreen.id = "atlasExitFullscreen";
  exitFullscreen.textContent = "✕ Zavřít";
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

    // Allow a later retry after an unsuccessful download.
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

    // Some browser distributions register automatically;
    // others expose a registration function.
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

    // Keep WebGL affordable on mobile devices.
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

  function resizeGraphs() {
    if (mode === "3d" && graph3D) {
      graph3D
        .width(Math.max(host3D.clientWidth, 1))
        .height(Math.max(host3D.clientHeight, 1));
    } else if (cy) {
      cy.resize();
    }
  }

  function renderThreeD() {
    if (!graph3D || mode !== "3d") return;

    const graph = getVisibleData();
    if (!graph) return;

    graph3D
      .graphData({
        nodes: graph.nodes,
        links: graph.links
      })
      .width(Math.max(host3D.clientWidth, 1))
      .height(Math.max(host3D.clientHeight, 1));

    report(
      "3D · " + graph.nodes.length + " souborů · " +
      graph.links.length + " vazeb" +
      (graph.limited ? " · omezený výběr kvůli výkonu" : "")
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

  async function enableThreeD() {
    if (switching) return;
    switching = true;
    modeButton.disabled = true;

    try {
      if (!supportsWebGL()) {
        throw new Error("Na tomto zařízení není dostupné WebGL.");
      }

      report("Připravuji prostorový renderer…");

      // This is the only place where the 3D library is loaded.
      await loadScript(THREE_URL);

      if (typeof window.ForceGraph3D !== "function") {
        throw new Error("3D knihovna se nenačetla správně.");
      }

      mode = "3d";
      ++layoutTicket;

      graph2D.style.display = "none";
      host3D.style.display = "block";

      // The old 2D instance is released; its configuration remains.
      if (cy) {
        cy.destroy();
        cy = null;
      }

      graph3D = new window.ForceGraph3D(host3D, {
        controlType: "trackball",
        rendererConfig: {antialias: false, alpha: false}
      });

      graph3D
        .backgroundColor("#090e18")
        .nodeRelSize(5)
        .nodeResolution(5)
        .nodeColor(node => node.color)
        .nodeLabel(node => escapeText(node.name))
        .linkColor(link => link.color)
        .linkOpacity(0.40)
        .linkWidth(0.7)
        .enableNodeDrag(false)
        .cooldownTicks(120)
        .onNodeClick(node => choose(node.id))
        .onLinkClick(link => {
          if (link.atlasEdge) explain(link.atlasEdge);
        });

      renderThreeD();

      modeButton.textContent = "↩ Vrátit 2D";
      modeButton.setAttribute("aria-pressed", "true");

    } catch (error) {
      console.error(error);

      // Return to the known working 2D path.
      mode = "2d";
      graph2D.style.display = "";
      host3D.style.display = "none";

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

        // Release GPU resources when switching back to 2D.
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
    graph2D.style.display = "";

    modeButton.textContent = "◈ Zapnout 3D";
    modeButton.setAttribute("aria-pressed", "false");

    // Keep the selected source file, filters, palette and 2D layout.
    window.renderGraph();
  }

  modeButton.setAttribute("aria-pressed", "false");

  modeButton.onclick = () => {
    if (mode === "3d") disableThreeD();
    else enableThreeD();
  };

  // Keep the existing Fit button meaningful in both modes.
  fitButton.addEventListener("click", () => {
    if (mode === "3d" && graph3D) {
      graph3D.zoomToFit(500, 65);
    }
  });

  fullscreenButton.onclick = async () => {
    try {
      if (!document.fullscreenElement) {
        if (!viewport.requestFullscreen) {
          throw new Error("Fullscreen API není podporováno.");
        }
        await viewport.requestFullscreen();
      } else {
        await document.exitFullscreen();
      }
    } catch (error) {
      report("Fullscreen není dostupný: " + error.message);
    }
  };

  exitFullscreen.onclick = () => {
    if (document.fullscreenElement) {
      document.exitFullscreen();
    }
  };

  document.addEventListener("fullscreenchange", () => {
    requestAnimationFrame(() => {
      resizeGraphs();

      if (mode === "2d" && cy) {
        cy.fit(undefined, 40);
      }
    });
  });

  if (typeof ResizeObserver !== "undefined") {
    new ResizeObserver(() => resizeGraphs()).observe(viewport);
  } else {
    window.addEventListener("resize", resizeGraphs);
  }

  // Save battery when switching browser tabs.
  document.addEventListener("visibilitychange", () => {
    if (mode !== "3d" || !graph3D) return;

    if (document.hidden) graph3D.pauseAnimation();
    else graph3D.resumeAnimation();
  });

  // Default mode is always 2D, regardless of previous 3D usage.
})();
