/**
 * Copy text, and say whether it really happened (UI audit J5-F17).
 *
 * `navigator.clipboard` exists only in a secure context (HTTPS or localhost), so on a plain
 * `http://` LAN install it is undefined and calling it throws; with the permission denied the
 * write rejects. Every copy button used to say "✓ copied" regardless. Callers show "copied" only
 * on `true`, and otherwise leave the text selectable for a manual copy.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    const clipboard = globalThis.navigator?.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') return false;
    await clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}
