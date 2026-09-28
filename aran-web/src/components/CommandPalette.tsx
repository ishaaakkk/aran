import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Home, Folder, Database, FileText, Layers, History, Package, TerminalSquare,
  ShieldCheck, Search,
} from 'lucide-react';
import { useAuthStore } from '../stores/authStore';
import { matchCommands, paletteCommands, type PaletteCommand } from '../lib/commandPalette';

/**
 * The ⌘K command palette. The pill in the top bar advertised this for months with no
 * handler behind it — a dead affordance on every screen (redesign brief, chrome HIGH #2).
 * Navigation-only on purpose (lib/commandPalette.ts says why); the pill says "Go to…".
 */

const ICONS: Record<PaletteCommand['icon'], React.ReactNode> = {
  home: <Home size={14} />,
  folder: <Folder size={14} />,
  database: <Database size={14} />,
  file: <FileText size={14} />,
  layers: <Layers size={14} />,
  history: <History size={14} />,
  package: <Package size={14} />,
  terminal: <TerminalSquare size={14} />,
  shield: <ShieldCheck size={14} />,
};

type Command = PaletteCommand;

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
}

export const CommandPalette: React.FC<CommandPaletteProps> = ({ open, onClose }) => {
  const navigate = useNavigate();
  const isOwner = useAuthStore((s) => s.activeTenant?.role === 'OWNER');
  const [query, setQuery] = useState('');
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const commands = useMemo<Command[]>(() => paletteCommands(isOwner), [isOwner]);

  const matches = useMemo(() => matchCommands(commands, query), [commands, query]);

  useEffect(() => {
    if (open) {
      // Reset per open — a palette remembering last search reads as broken.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setQuery('');
      setCursor(0);
      inputRef.current?.focus();
    }
  }, [open]);

  if (!open) return null;

  const run = (cmd: Command | undefined) => {
    if (!cmd) return;
    onClose();
    navigate(cmd.to);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setCursor((c) => Math.min(c + 1, matches.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setCursor((c) => Math.max(c - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      run(matches[cursor]);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[60] flex items-start justify-center pt-[18vh] bg-black/50 backdrop-blur-[2px]"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        data-testid="command-palette"
        className="w-full max-w-lg mx-4 glass rounded-lg border border-border shadow-2xl overflow-hidden animate-in"
      >
        <div className="flex items-center gap-2.5 px-4 h-11 border-b border-border">
          <Search size={14} className="text-muted-foreground shrink-0" />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => { setQuery(e.target.value); setCursor(0); }}
            onKeyDown={onKeyDown}
            placeholder="Go to…"
            data-testid="command-palette-input"
            className="flex-1 bg-transparent outline-none font-mono text-[13px] text-foreground placeholder:text-muted-foreground"
          />
          <kbd className="font-mono text-[10.5px] text-muted-foreground/60 border border-border rounded px-1.5 py-0.5">esc</kbd>
        </div>
        <div className="max-h-72 overflow-y-auto py-1.5">
          {matches.length === 0 ? (
            <div className="px-4 py-3 font-mono text-[12px] text-muted-foreground">No page matches. This goes to pages only — open a list to find a dataset or class.</div>
          ) : (
            matches.map((c, i) => (
              <button
                key={c.to}
                type="button"
                onClick={() => run(c)}
                onMouseEnter={() => setCursor(i)}
                className={`w-full flex items-center gap-3 px-4 h-9 text-left font-mono text-[12.5px] transition-colors ${
                  i === cursor
                    ? 'bg-primary/10 text-primary'
                    : 'text-foreground hover:bg-muted/40'
                }`}
              >
                <span className="shrink-0">{ICONS[c.icon]}</span>
                <span className="font-semibold">{c.label}</span>
                <span className="ml-auto text-[11px] text-muted-foreground">{c.hint}</span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

export default CommandPalette;
