import React, { useState, useRef, useMemo } from 'react';
import { WorkbenchClass, Suggestion, CLOSED_COMPLETION, completeOnInput, completeOnRequest, completionKey, type CompletionState } from '../../lib/workbenchModel';
import type { QueryCatalog } from '@/services/types';

// OqlParser's keywords and nothing else — highlighting ON, IN or LIKE implied syntax it refuses.
const KW: Record<string, boolean> = {};
['SELECT', 'DISTINCT', 'FROM', 'WHERE', 'LIMIT', 'AND', 'OR', 'ORDER', 'GROUP', 'HAVING', 'BY', 'AS', 'ASC', 'DESC', 'IS', 'NOT', 'NULL', 'TRUE', 'FALSE', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX'].forEach(k => { KW[k] = true; });

interface Token {
  t: string;
  c: string;
}

function tokenize(src: string, classByName: Record<string, WorkbenchClass>): Token[] {
  const out: Token[] = [];
  const RE = /(--[^\n]*)|('(?:[^'\\]|\\.)*'?)|(\[[A-Za-z_]\w*\])|([A-Za-z_]\w*\.(?:[A-Za-z_]\w*|\*))|([A-Za-z_]\w*)|(\d+\.?\d*)|(->|<-|-\[|\]->|\]-)|(\s+)|([\s\S])/g;
  let m;
  while ((m = RE.exec(src)) !== null) {
    if (m[1]) out.push({ t: m[1], c: 'tok-com' });
    else if (m[2]) out.push({ t: m[2], c: 'tok-str' });
    else if (m[3]) out.push({ t: m[3], c: 'tok-rel' });
    else if (m[4]) {
      const parts = m[4].split('.');
      out.push({ t: parts[0], c: classByName[parts[0]] ? 'tok-cls' : 'tok-id' });
      out.push({ t: '.', c: 'tok-pun' });
      out.push({ t: parts[1], c: parts[1] === '*' ? 'tok-pun' : 'tok-fld' });
    }
    else if (m[5]) {
      const up = m[5].toUpperCase();
      if (KW[up]) out.push({ t: m[5], c: 'tok-kw' });
      else if (classByName[m[5]]) out.push({ t: m[5], c: 'tok-cls' });
      else out.push({ t: m[5], c: 'tok-id' });
    }
    else if (m[6]) out.push({ t: m[6], c: 'tok-num' });
    else if (m[7]) out.push({ t: m[7], c: 'tok-arr' });
    else out.push({ t: m[m.length - 1] || m[8], c: 'tok-pun' });
  }
  return out;
}

function caretCoords(ta: HTMLTextAreaElement) {
  const div = document.createElement('div');
  const s = getComputedStyle(ta);
  // Mirror the textarea's text metrics onto the measuring div, so the caret
  // rectangle we read off it lines up with the real one.
  const copied = [
    'fontFamily', 'fontSize', 'fontWeight', 'lineHeight', 'letterSpacing',
    'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft', 'textIndent',
  ] as const;
  copied.forEach(p => {
    div.style[p] = s[p];
  });
  div.style.position = 'absolute'; div.style.visibility = 'hidden';
  div.style.whiteSpace = 'pre-wrap'; div.style.wordWrap = 'break-word';
  div.style.width = ta.clientWidth + 'px'; div.style.boxSizing = 'border-box';
  const before = ta.value.slice(0, ta.selectionStart);
  div.textContent = before;
  const span = document.createElement('span'); span.textContent = '\u200b';
  div.appendChild(span);
  document.body.appendChild(div);
  const top = span.offsetTop, left = span.offsetLeft;
  document.body.removeChild(div);
  return { top, left };
}

const KIND_COLOR: Record<string, string> = { keyword: '#8B5CF6', class: '#3DCEA0', field: '#5BD2F0', key: '#E8B339', relation: '#5E9CE8', all: '#7E7E8A' };
const KIND_GLYPH: Record<string, string> = { keyword: 'kw', class: 'C', field: 'f', key: 'pk', relation: 'rel', all: '*' };

const LIMIT_OPTIONS = [10, 100, 1000, 5000];

interface OqlEditorProps {
  value: string;
  onChange: (val: string) => void;
  onRun?: () => void;
  onExplain?: () => void;
  onCancel?: () => void;
  running?: boolean;
  minRows?: number;
  focusClass?: string | null;
  autocomplete?: boolean;
  catalog: QueryCatalog | null;
  catalogData: { classByName: Record<string, WorkbenchClass> };
  limit?: number;
  onLimitChange?: (l: number) => void;
  /** Shown in the editor bar: whether the text is the builder's or hand-edited (J5-F4). */
  status?: React.ReactNode;
}

export function OqlEditor(props: OqlEditorProps) {
  const { value, onChange, onRun, onExplain, onCancel, running, minRows = 5, focusClass, autocomplete = true, catalog, catalogData, limit = 100, onLimitChange, status } = props;
  
  const taRef = useRef<HTMLTextAreaElement>(null);
  const [comp, setComp] = useState<CompletionState>(CLOSED_COMPLETION);
  const { open, items, idx } = comp;
  const [pos, setPos] = useState({ top: 0, left: 0 });

  const tokens = useMemo(() => tokenize(value, catalogData.classByName), [value, catalogData.classByName]);

  /** Place the list under the caret. */
  function placeAt(ta: HTMLTextAreaElement) {
    const c = caretCoords(ta);
    setPos({ top: c.top - ta.scrollTop + 22, left: Math.min(c.left - ta.scrollLeft, ta.clientWidth - 220) });
  }

  function show(next: CompletionState) {
    const ta = taRef.current;
    if (autocomplete === false || !ta) { setComp(CLOSED_COMPLETION); return; }
    if (next.open) placeAt(ta);
    setComp(next);
  }

  function accept(it: Suggestion) {
    const ta = taRef.current; if (!ta) return;
    const caret = ta.selectionStart;
    const start = caret - it.replace;
    const nv = value.slice(0, start) + it.insert + value.slice(caret);
    onChange(nv);
    setComp(CLOSED_COMPLETION);
    const newCaret = start + it.insert.length;
    requestAnimationFrame(() => { ta.focus(); ta.setSelectionRange(newCaret, newCaret); });
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    switch (completionKey(comp, e.key)) {
      case 'next': e.preventDefault(); setComp(c => ({ ...c, idx: (c.idx + 1) % c.items.length, moved: true })); return;
      case 'prev': e.preventDefault(); setComp(c => ({ ...c, idx: (c.idx - 1 + c.items.length) % c.items.length, moved: true })); return;
      case 'accept': e.preventDefault(); accept(items[idx]); return;
      case 'close':
        setComp(CLOSED_COMPLETION);
        if (e.key === 'Escape') { e.preventDefault(); return; }
        break; // Enter with no moved highlight: the list closes and the newline is typed as usual.
      case 'pass': break;
    }
    if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key === 'Enter') { e.preventDefault(); onExplain?.(); return; }
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); onRun?.(); return; }
    if (e.key === ' ' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      const ta = e.currentTarget;
      show(completeOnRequest(ta.value, ta.selectionStart, catalog));
      return;
    }
  }

  /** The list follows the text this event produced — never the previous render's (J5-F3). */
  function onInput(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const next = e.target.value;
    const caret = e.target.selectionStart;
    onChange(next);
    show(completeOnInput(comp, next, caret, catalog));
  }

  function syncScroll() {
    const ta = taRef.current; if (!ta) return;
    const pre = ta.previousElementSibling as HTMLElement;
    if (pre) { pre.scrollTop = ta.scrollTop; pre.scrollLeft = ta.scrollLeft; }
  }

  const rows = Math.max(minRows, value.split('\n').length);

  return (
    <div id="tour-oql-editor" className="editor">
      <div className="editor-bar">
        <div className="editor-bar-l">
          <span className="prompt">OQL · unified graph</span>
          {status}
        </div>
        {focusClass && <span className="editor-tab">▤ {focusClass}</span>}
      </div>
      <div className="editor-body" style={{ minHeight: `${rows * 22 + 28}px` }}>
        <pre className="editor-hl" aria-hidden="true">
          {tokens.map((tk, i) => <span key={i} className={tk.c}>{tk.t}</span>)}
          <span>{'\n'}</span>
        </pre>
        <textarea
          ref={taRef} className="editor-ta" value={value} spellCheck={false}
          data-testid="oql-editor"
          onChange={onInput} onKeyDown={onKeyDown} onScroll={syncScroll}
          // Moving the caret is not a request for suggestions; the list closes rather than
          // following the caret into text it was not opened for.
          onClick={() => setComp(CLOSED_COMPLETION)}
          onKeyUp={(e) => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) setComp(CLOSED_COMPLETION); }}
          onBlur={() => setTimeout(() => setComp(CLOSED_COMPLETION), 150)}
          aria-autocomplete="list"
          placeholder="SELECT ClassName.* …  (⌘⏎ run · ⌘space suggest)"
        />
        {open && (
          <div className="ac" style={{ top: `${pos.top}px`, left: `${pos.left}px` }}>
            {items.slice(0, 8).map((it, i) => (
              <div key={i} className={`ac-item${i === idx ? ' is-on' : ''}`}
                   onMouseDown={ev => { ev.preventDefault(); accept(it); }}
                   onMouseEnter={() => setComp(c => ({ ...c, idx: i, moved: true }))}
                   role="button"
                   tabIndex={0}
                   onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') accept(it); }}>
                <span className="ac-glyph" style={{ color: KIND_COLOR[it.kind] || '#7E7E8A', borderColor: `${KIND_COLOR[it.kind] || '#7E7E8A'}55` }}>
                  {KIND_GLYPH[it.kind] || '?'}
                </span>
                <span className="ac-label">{it.label}</span>
                <span className="ac-detail">{it.detail}</span>
              </div>
            ))}
          </div>
        )}
      </div>
      <div className="editor-foot">
        <span className="editor-hint">⌘⏎ run · ⌘⇧⏎ explain · ⌘space suggest · Tab accepts</span>
        <div style={{ flex: 1 }} />
        {onLimitChange && (
          <div className="seg limit-seg" title="LIMIT rows returned">
            {LIMIT_OPTIONS.map(l => (
              <button key={l} className={`seg-btn${limit === l ? ' is-on' : ''}`} onClick={() => onLimitChange(l)}>
                {l >= 1000 ? `${l / 1000}k` : l}
              </button>
            ))}
          </div>
        )}
        <button className="btn btn-ghost" onClick={onExplain} disabled={running}>
          <span className="btn-i">▥</span>explain
        </button>
        {running && onCancel ? (
          // D7: there is no server-side cancel, so the button promises only what it does.
          <button
            className="btn btn-cancel"
            data-testid="oql-stop-waiting"
            title="Stop waiting for this answer. Aran cannot cancel a query already running on the server; it may keep running for up to about two minutes."
            onClick={onCancel}
          >
            <span className="btn-i">■</span>stop waiting
          </button>
        ) : (
          <button className="btn btn-primary" id="tour-oql-run" data-testid="oql-run" onClick={onRun} disabled={running}>
            {running ? <span className="spin" /> : <span className="btn-i">▶</span>}
            {running ? 'running…' : 'run'}
          </button>
        )}
      </div>
    </div>
  );
}
