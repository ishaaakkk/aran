/**
 * The command palette's destinations and matching, out of the component so they are asserted
 * without a DOM (and the component file stays component-only).
 *
 * Navigation-only on purpose: destinations are the one command set that can never drift from
 * what the app can do. Searching objects (a dataset, a class) is Tier C's (UI audit J8-F17), so
 * the pill says "Go to…", not "Search". `keywords` are the words people type for a destination
 * that are not its name (J7-F20: "users" and "tokens" found nothing).
 */
export interface PaletteCommand {
  label: string;
  hint: string;
  to: string;
  keywords?: string;
  icon: 'home' | 'folder' | 'database' | 'file' | 'layers' | 'history' | 'package' | 'terminal' | 'shield';
}

export function paletteCommands(isOwner: boolean): PaletteCommand[] {
  const nav: PaletteCommand[] = [
    { label: 'Home', hint: 'dashboard', icon: 'home', to: '/dashboard' },
    { label: 'Projects', hint: 'workspaces', icon: 'folder', to: '/projects' },
    { label: 'Repositories', hint: 'connect · sources', icon: 'database', to: '/repositories', keywords: 'connections databases taps iceberg' },
    { label: 'Datasets', hint: 'connect · bootstrapped data', icon: 'file', to: '/datasets', keywords: 'tables files uploads' },
    { label: 'Alignments', hint: 'integrate · build the model', icon: 'layers', to: '/alignments', keywords: 'mappings join discovery compile rebuild' },
    { label: 'Schema versions', hint: 'integrate · promote & rollback', icon: 'history', to: '/versions', keywords: 'history diff' },
    { label: 'Data products', hint: 'consume · published views', icon: 'package', to: '/products', keywords: 'jdbc agents mcp iceberg cubes' },
    { label: 'Query workbench', hint: 'consume · OQL & SQL', icon: 'terminal', to: '/oql', keywords: 'query run explain' },
  ];
  if (isOwner) {
    nav.push({
      label: 'Administration',
      hint: 'owners only',
      icon: 'shield',
      to: '/admin',
      keywords: 'users people members invite licence license tokens credentials usage storage activity audit system',
    });
  }
  return nav;
}

export function matchCommands(commands: PaletteCommand[], query: string): PaletteCommand[] {
  const q = query.trim().toLowerCase();
  if (!q) return commands;
  return commands.filter((c) =>
    [c.label, c.hint, c.keywords ?? ''].some((s) => s.toLowerCase().includes(q)),
  );
}

/** The palette shortcut as this machine spells it. `platform` is `navigator.platform`. */
export function shortcutLabel(platform: string | undefined): string {
  return platform && /mac|iphone|ipad/i.test(platform) ? '⌘K' : 'Ctrl+K';
}
