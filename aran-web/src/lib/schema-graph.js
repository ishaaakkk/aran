// =============================================================================
// Aran — Schema Graph (D3 force-directed)
// =============================================================================
//
// Framework-agnostic. Pure JS. Depends on d3 v7 being available globally
// (either via <script src="https://unpkg.com/d3@7/dist/d3.min.js"></script>
// or via a bundler that exposes d3 to the runtime).
//
// USAGE (HTML / vanilla JS)
//   const graph = AranSchemaGraph.create(containerEl, {
//     nodes: [{ id: 'Patient', type: 'integrated' }, ...],
//     edges: [['Patient', 'Practitioner', 'treatedBy'], ...],
//     selected: 'Patient',
//     theme: AranSchemaGraph.DARK_THEME,   // or LIGHT_THEME, or your own
//     onSelect: (node) => { /* update inspector */ },
//   });
//   graph.fit();           // re-fit to viewport
//   graph.zoomBy(1.2);     // zoom in
//   graph.select('Person');// programmatic selection
//   graph.destroy();       // teardown
//
// USAGE (Vue 3 / Composition API)
//   <template>
//     <div ref="graphEl" class="schema-graph"></div>
//   </template>
//
//   <script setup>
//   import { onMounted, onBeforeUnmount, ref } from 'vue'
//   // either: import the file via your bundler (sets window.AranSchemaGraph),
//   // or expose it on globalThis from a boot file.
//   const graphEl = ref(null)
//   let graph = null
//
//   onMounted(() => {
//     graph = window.AranSchemaGraph.create(graphEl.value, {
//       nodes, edges, onSelect: (n) => emit('select', n),
//     })
//   })
//   onBeforeUnmount(() => graph?.destroy())
//   </script>
//
// =============================================================================

(function (global, factory) {
  if (typeof exports === 'object' && typeof module !== 'undefined') {
    module.exports = factory();
  } else {
    global.AranSchemaGraph = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const DEFAULTS = {
    nodeWidth: 116,
    nodeHeight: 28,
    linkDistance: 130,
    leafLinkDistance: 78,    // shorter for leaf-like nodes (datatype, xsd)
    chargeStrength: -640,
    collideRadius: 60,
    leafCollideRadius: 38,
    arrowSize: 6,
    arrowRefX: 22,           // pulls arrow back so it doesn't stab the rect
    fitPadding: 0.92,        // < 1 to leave margin around the graph
    settleMs: 700,           // an early first fit, before the layout has settled
  };

  const DARK_THEME = {
    card:           '#1B1B24',
    ink:            '#E8E8EE',
    muted:          '#7E7E8A',
    hair:           '#2D2D38',
    accent:         '#E8B339',
    edge:           '#2D2D38',
    edgeActive:     '#E8B339',
    edgeLabel:      '#7E7E8A',
    edgeLabelActive:'#E8B339',
    types: {
      class:      '#9C8CF8',
      integrated: '#3DCEA0',
      object:     '#5E9CE8',
      datatype:   '#D67BF0',
      xsd:        '#F08E5E',
    },
    fontMono: "'JetBrains Mono', ui-monospace, monospace",
  };

  const LIGHT_THEME = {
    card:           '#FFFFFF',
    ink:            '#15151B',
    muted:          '#5C5C66',
    hair:           '#D3D0C5',
    accent:         '#E8B339',
    edge:           '#D3D0C5',
    edgeActive:     '#A56E1A',
    edgeLabel:      '#5C5C66',
    edgeLabelActive:'#A56E1A',
    types: {
      class:      '#5C49C4',
      integrated: '#1F8A5B',
      object:     '#2A5FB8',
      datatype:   '#9032AE',
      xsd:        '#B8551E',
    },
    fontMono: "'JetBrains Mono', ui-monospace, monospace",
  };

  function isLeafType(t) { return t === 'datatype' || t === 'xsd'; }

  // J4-F3: every node is a keyboard-focusable button with a name — before this the graph was the
  // only way to pick an element for a manual alignment and none of its nodes could take focus, so
  // a keyboard or screen-reader user could not create one at all. The name says what a sighted
  // user reads off the node: its kind, its label and (F18) the source it came from.
  function nodeAccessibleName(d) {
    const kind = { datatype: 'property', object: 'relationship', xsd: 'datatype', integrated: 'integrated class' }[d.type]
      || d.type || 'class';
    const name = d.label || d.id;
    return d.sublabel ? `${kind} ${name}, from ${d.sublabel}` : `${kind} ${name}`;
  }

  /** Enter and Space activate a focused node, as they would a button. */
  function activatesNode(key) { return key === 'Enter' || key === ' ' || key === 'Spacebar'; }

  // Fits the view when the force layout settles — the simulation's own 'end' — instead of only at
  // a wall-clock guess: under load a layout still expanding at the guess stayed unfitted, with
  // nodes outside the clipped pane where a click could not reach them (e2e b1/b2). The timer stays
  // as an early first fit. The first settle ends auto-fitting, and so does any pan or zoom the
  // person makes before it: from then on the view is theirs.
  function autoFit(sim, fit, settleMs) {
    let active = true;
    const timer = setTimeout(() => { if (active) fit(); }, settleMs);
    sim.on('end.autofit', () => {
      sim.on('end.autofit', null);
      if (active) fit();
      active = false;
    });
    return {
      userMoved() { active = false; },
      dispose() {
        clearTimeout(timer);
        sim.on('end.autofit', null);
      },
    };
  }

  function create(container, userOptions) {
    if (typeof d3 === 'undefined') {
      throw new Error('[AranSchemaGraph] d3 v7 is not available on the global scope. Include https://unpkg.com/d3@7/dist/d3.min.js before this script.');
    }
    if (!(container instanceof Element)) {
      throw new Error('[AranSchemaGraph] first arg must be a DOM Element.');
    }

    const opts = Object.assign({}, DEFAULTS, userOptions || {});
    const theme = opts.theme || DARK_THEME;

    // Wipe + measure
    container.innerHTML = '';
    let w = container.clientWidth || 800;
    let h = container.clientHeight || 480;

    // Normalise data
    const nodes = (opts.nodes || []).map(n => ({ ...n }));
    const nodeIds = new Set(nodes.map(n => n.id));
    const links = (opts.edges || [])
      .map(e => Array.isArray(e)
        ? { source: e[0], target: e[1], label: e[2] || '' }
        : { source: e.source, target: e.target, label: e.label || '' })
      .filter(l => nodeIds.has(l.source) && nodeIds.has(l.target));

    // Optional initial seed positions (n.x / n.y as 0..1 fractions of viewport)
    nodes.forEach(n => {
      if (typeof n.x_init === 'number') n.x = n.x_init * w;
      if (typeof n.y_init === 'number') n.y = n.y_init * h;
    });

    let selectedId = opts.selected || null;

    // ── SVG ──────────────────────────────────────────────────────────────────
    const svg = d3.select(container).append('svg')
      .attr('width', '100%')
      .attr('height', '100%')
      .attr('role', 'group')
      .attr('aria-label', opts.ariaLabel || 'Schema graph')
      .style('display', 'block')
      .style('background', 'transparent');

    // Defs — arrow markers
    const defs = svg.append('defs');
    [['arrow-default', theme.edge], ['arrow-active', theme.edgeActive]].forEach(([id, color]) => {
      defs.append('marker')
        .attr('id', id)
        .attr('viewBox', '0 0 10 10')
        .attr('refX', opts.arrowRefX).attr('refY', 5)
        .attr('markerWidth', opts.arrowSize)
        .attr('markerHeight', opts.arrowSize)
        .attr('orient', 'auto-start-reverse')
        .append('path')
          .attr('d', 'M0,0 L10,5 L0,10 z')
          .attr('fill', color);
    });

    // Pan/zoom group
    const zoomG  = svg.append('g').attr('class', 'aran-schema-zoom');
    const linksG = zoomG.append('g').attr('class', 'aran-schema-links');
    const labelsG= zoomG.append('g').attr('class', 'aran-schema-edge-labels');
    const nodesG = zoomG.append('g').attr('class', 'aran-schema-nodes');

    const zoom = d3.zoom()
      .scaleExtent([0.2, 4])
      .filter(event => {
        // disable zoom-on-dblclick, but allow trackpad/mousewheel gestures (wheel events with ctrlKey)
        return (!event.ctrlKey || event.type === 'wheel') && event.type !== 'dblclick';
      })
      .on('zoom', e => {
        zoomG.attr('transform', e.transform);
        // A transform with a source event is the person's own pan or zoom; fit()'s has none.
        if (e.sourceEvent && fitting) fitting.userMoved();
      });
    let fitting = null;
    svg.call(zoom).on('dblclick.zoom', null);
    svg.on('dblclick', () => fit());

    // ── Helpers ──────────────────────────────────────────────────────────────
    function isActiveLink(d) {
      if (!selectedId) return false;
      const s = d.source.id || d.source;
      const t = d.target.id || d.target;
      return s === selectedId || t === selectedId;
    }

    // ── Simulation ───────────────────────────────────────────────────────────
    const sim = d3.forceSimulation(nodes)
      .force('link', d3.forceLink(links).id(d => d.id)
        .distance(l => isLeafType((l.target.type) || nodes.find(n => n.id === l.target)?.type) ? opts.leafLinkDistance : opts.linkDistance)
        .strength(0.45))
      .force('charge', d3.forceManyBody().strength(opts.chargeStrength))
      .force('center', d3.forceCenter(w / 2, h / 2))
      .force('collide', d3.forceCollide(d => isLeafType(d.type) ? opts.leafCollideRadius : opts.collideRadius))
      .alphaDecay(0.04);

    // ── Render: links ────────────────────────────────────────────────────────
    const linkSel = linksG.selectAll('line').data(links).join('line')
      .attr('stroke-width', 1)
      .attr('marker-end', d => isActiveLink(d) ? 'url(#arrow-active)' : 'url(#arrow-default)')
      .attr('stroke', d => isActiveLink(d) ? theme.edgeActive : theme.edge)
      .attr('stroke-dasharray', d => isActiveLink(d) ? null : '3 3');

    const labelSel = labelsG.selectAll('text').data(links).join('text')
      .attr('font-family', theme.fontMono)
      .attr('font-size', 10)
      .attr('text-anchor', 'middle')
      .attr('pointer-events', 'none')
      .attr('fill', d => isActiveLink(d) ? theme.edgeLabelActive : theme.edgeLabel)
      .text(d => d.label);

    // ── Render: nodes ────────────────────────────────────────────────────────
    const nodeSel = nodesG.selectAll('g.aran-schema-node').data(nodes, d => d.id).join('g')
      .attr('class', 'aran-schema-node')
      // e2e hook: keyed off the node's display label (falling back to its id),
      // same local-name text every node already shows — not view-scoped, since
      // this canvas is mounted by more than one view. A caller that needs to
      // disambiguate same-named nodes across two simultaneously-mounted
      // canvases (e.g. AlignmentsView's left/right panels) should scope its
      // locator to the containing element instead.
      .attr('data-testid', d => `schema-node-${d.label || d.id}`)
      .attr('tabindex', 0)
      .attr('role', 'button')
      .attr('aria-label', d => nodeAccessibleName(d))
      .attr('aria-pressed', d => String(d.id === selectedId))
      .style('cursor', 'pointer')
      .style('outline', 'none')
      .call(d3.drag()
        .on('start', (e, d) => {
          if (!e.active) sim.alphaTarget(0.3).restart();
          d.fx = d.x; d.fy = d.y;
        })
        .on('drag',  (e, d) => { d.fx = e.x; d.fy = e.y; })
        .on('end',   (e, d) => {
          if (!e.active) sim.alphaTarget(0);
          // keep dragged position; users can right-click to release (below)
        })
      )
      .on('click', (e, d) => { e.stopPropagation(); select(d.id); })
      .on('keydown', (e, d) => {
        if (!activatesNode(e.key)) return;
        e.preventDefault();
        e.stopPropagation();
        select(d.id);
      })
      .on('focus', function () { d3.select(this).select('rect.aran-focus-ring').attr('opacity', 1); })
      .on('blur', function () { d3.select(this).select('rect.aran-focus-ring').attr('opacity', 0); })
      .on('contextmenu', (e, d) => {
        // right-click releases a pinned node back into the simulation
        e.preventDefault();
        d.fx = null; d.fy = null;
        sim.alpha(0.3).restart();
      });

    // Click outside to deselect
    svg.on('click', () => select(null));

    // The keyboard focus ring: the browser's own outline is clipped or absent on SVG groups.
    nodeSel.append('rect')
      .attr('class', 'aran-focus-ring')
      .attr('width', opts.nodeWidth + 8)
      .attr('height', opts.nodeHeight + 8)
      .attr('x', -opts.nodeWidth / 2 - 4)
      .attr('y', -opts.nodeHeight / 2 - 4)
      .attr('rx', 5)
      .attr('fill', 'none')
      .attr('stroke', theme.accent)
      .attr('stroke-width', 2)
      .attr('opacity', 0)
      .attr('pointer-events', 'none');

    nodeSel.append('rect')
      .attr('class', 'aran-node-box')
      .attr('width', opts.nodeWidth)
      .attr('height', opts.nodeHeight)
      .attr('x', -opts.nodeWidth / 2)
      .attr('y', -opts.nodeHeight / 2)
      .attr('rx', 3)
      .attr('fill', theme.card)
      .attr('stroke', d => d.id === selectedId ? theme.accent : theme.hair)
      .attr('stroke-width', d => d.id === selectedId ? 1.6 : 1);

    nodeSel.append('circle')
      .attr('cx', -opts.nodeWidth / 2 + 13)
      .attr('cy', 0)
      .attr('r', 4)
      .attr('fill', d => theme.types[d.type] || theme.types.class);

    nodeSel.append('text')
      // Nodes carrying a sublabel (F18: the source qualifier) get a two-line layout;
      // plain nodes keep the original vertically-centered single line.
      .attr('x', -opts.nodeWidth / 2 + 23)
      .attr('y', d => d.sublabel ? -1 : 4)
      .attr('font-family', theme.fontMono)
      .attr('font-size', 12)
      .attr('font-weight', 500)
      .attr('fill', theme.ink)
      .text(d => d.label || d.id);

    nodeSel.filter(d => !!d.sublabel).append('text')
      .attr('x', -opts.nodeWidth / 2 + 23)
      .attr('y', 11)
      .attr('font-family', theme.fontMono)
      .attr('font-size', 9.5)
      .attr('fill', theme.ink)
      .attr('opacity', 0.55)
      .text(d => d.sublabel);

    // ── Tick ─────────────────────────────────────────────────────────────────
    sim.on('tick', () => {
      linkSel
        .attr('x1', d => d.source.x).attr('y1', d => d.source.y)
        .attr('x2', d => d.target.x).attr('y2', d => d.target.y);
      labelSel
        .attr('x', d => (d.source.x + d.target.x) / 2)
        .attr('y', d => (d.source.y + d.target.y) / 2 - 4);
      nodeSel.attr('transform', d => `translate(${d.x},${d.y})`);
    });

    // ── Public API ───────────────────────────────────────────────────────────
    function select(id) {
      selectedId = id;
      linkSel
        .attr('stroke', d => isActiveLink(d) ? theme.edgeActive : theme.edge)
        .attr('stroke-dasharray', d => isActiveLink(d) ? null : '3 3')
        .attr('marker-end', d => isActiveLink(d) ? 'url(#arrow-active)' : 'url(#arrow-default)');
      labelSel
        .attr('fill', d => isActiveLink(d) ? theme.edgeLabelActive : theme.edgeLabel);
      nodeSel.select('rect.aran-node-box')
        .attr('stroke', d => d.id === selectedId ? theme.accent : theme.hair)
        .attr('stroke-width', d => d.id === selectedId ? 1.6 : 1);
      nodeSel.attr('aria-pressed', d => String(d.id === selectedId));
      if (typeof opts.onSelect === 'function') {
        opts.onSelect(selectedId ? nodes.find(n => n.id === selectedId) : null);
      }
    }

    function fit() {
      const bbox = zoomG.node().getBBox();
      if (!bbox || bbox.width === 0 || bbox.height === 0) return;
      const scale = opts.fitPadding * Math.min(w / bbox.width, h / bbox.height);
      const tx = w / 2 - scale * (bbox.x + bbox.width / 2);
      const ty = h / 2 - scale * (bbox.y + bbox.height / 2);
      svg.transition().duration(450)
        .call(zoom.transform, d3.zoomIdentity.translate(tx, ty).scale(scale));
    }

    function zoomBy(factor) {
      svg.transition().duration(200).call(zoom.scaleBy, factor);
    }

    function resize() {
      w = container.clientWidth || w;
      h = container.clientHeight || h;
      sim.force('center', d3.forceCenter(w / 2, h / 2)).alpha(0.3).restart();
    }

    function releaseAllPinned() {
      nodes.forEach(n => { n.fx = null; n.fy = null; });
      sim.alpha(0.6).restart();
    }

    function destroy() {
      fitting.dispose();
      sim.stop();
      ro && ro.disconnect();
      svg.remove();
    }

    fitting = autoFit(sim, fit, opts.settleMs);

    // Track container resize
    let ro = null;
    if (typeof ResizeObserver !== 'undefined') {
      ro = new ResizeObserver(() => resize());
      ro.observe(container);
    }

    return {
      select, fit, zoomBy, resize, releaseAllPinned, destroy,
      getSelected: () => selectedId,
      getNodes: () => nodes,
      // expose the underlying simulation for advanced cases
      _sim: sim, _svg: svg.node(),
    };
  }

  return { create, autoFit, nodeAccessibleName, activatesNode, DARK_THEME, LIGHT_THEME };
});
