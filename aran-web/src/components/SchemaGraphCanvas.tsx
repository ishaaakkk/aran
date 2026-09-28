import React, { useState, useRef, useEffect } from 'react';
import type { GraphNode, GraphEdge } from '@/services/types';
import type { SchemaGraphInstance } from '@/lib/schema-graph';

/**
 * The D3 schema-graph canvas: mounts the untyped UMD force-graph bundle
 * (lib/schema-graph.js) into a div and tears it down on unmount. This is the
 * one wrapper for that engine — DatasetsListView and AlignmentsView used to
 * carry near-identical private copies.
 *
 * Not to be confused with components/workbench/GraphCanvas, the hand-rolled
 * SVG canvas used by the query workbench.
 */
interface SchemaGraphCanvasProps {
  graphData: { nodes: GraphNode[]; edges: GraphEdge[] } | null;
  onNodeSelect: (node: GraphNode | null) => void;
  /**
   * Controlled selection: when provided, prop changes are pushed into the
   * graph. Leave undefined for click-driven selection only.
   */
  selectedNode?: GraphNode | null;
  /** Optional external handle to the mounted instance (fit/zoom/resize). */
  instanceRef?: React.MutableRefObject<SchemaGraphInstance | null>;
  /**
   * Second label line per node, e.g. the source dataset's name. F18: two nodes named
   * "Author" from different sources were indistinguishable in the only selection
   * surface that exists — the qualifier is what tells them apart. Return null for
   * nodes that need no qualifier.
   */
  nodeSublabel?: (node: GraphNode) => string | null;
  /** The graph's accessible name — each node is a focusable button inside it (J4-F3). */
  ariaLabel?: string;
}

export const SchemaGraphCanvas: React.FC<SchemaGraphCanvasProps> = ({
  graphData, onNodeSelect, selectedNode, instanceRef: externalRef, nodeSublabel, ariaLabel,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const instanceRef = useRef<SchemaGraphInstance | null>(null);
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));

  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(document.documentElement.classList.contains('dark'));
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  // Push controlled selection into the mounted graph. Skipped entirely in
  // uncontrolled mode (prop undefined) so click selections aren't cleared.
  useEffect(() => {
    if (selectedNode === undefined || !instanceRef.current) return;
    const current = instanceRef.current.getSelected();
    const target = selectedNode?.id || null;
    if (current !== target) {
      instanceRef.current.select(target);
    }
  }, [selectedNode]);

  useEffect(() => {
    if (!containerRef.current || !graphData || graphData.nodes.length === 0) return;

    const mountGraph = async () => {
      const d3 = await import('d3');
      // The UMD bundle resolves d3 off the window rather than importing it.
      window.d3 = d3;
      const { default: schemaGraphModule } = await import('@/lib/schema-graph.js');
      const AranSchemaGraph = schemaGraphModule ?? window.AranSchemaGraph;
      if (!AranSchemaGraph) return;

      instanceRef.current?.destroy();

      instanceRef.current = AranSchemaGraph.create(containerRef.current!, {
        nodes: nodeSublabel
          ? graphData.nodes.map((n) => ({ ...n, sublabel: nodeSublabel(n) ?? undefined }))
          : graphData.nodes,
        edges: graphData.edges.map(e => [e.source, e.target, e.label]),
        theme: isDark ? AranSchemaGraph.DARK_THEME : AranSchemaGraph.LIGHT_THEME,
        selected: selectedNode?.id || null,
        onSelect: (node: GraphNode | null) => onNodeSelect(node),
        ariaLabel,
      });
      if (externalRef) externalRef.current = instanceRef.current;
    };

    mountGraph().catch(console.error);

    return () => {
      instanceRef.current?.destroy();
      instanceRef.current = null;
      if (externalRef) externalRef.current = null;
    };
    // Deliberately excludes externalRef/onNodeSelect/selectedNode: this (re)mounts an
    // imperative D3 force graph, which should happen only when the data or theme
    // actually changes — not on every parent render, and not on every selection
    // change, both of which would tear down and rebuild the whole visualization.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphData, isDark]);

  return (
    <div
      ref={containerRef}
      className="w-full h-full"
      style={{ background: 'transparent' }}
    />
  );
};
