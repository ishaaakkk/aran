/**
 * The Alignments header's one-word account of the integration model, from the state of its READ
 * (review of UA2). It used to say "Saving…"/"Saved" off the model's GET: "Saving…" on every page
 * load, where nothing was being saved, and a green "Saved" after the model had failed to load.
 * The hook tracks the read, not the writes, so that is all this may describe. Null when there is
 * no project to have a model.
 */
export interface ModelStatus {
  variant: 'ok' | 'warn' | 'err'
  label: string
}

export function modelStatus(s: { projectSelected: boolean; loading: boolean; error: string | null; loaded: boolean }): ModelStatus | null {
  if (!s.projectSelected) return null
  if (s.loading) return { variant: 'warn', label: 'Loading…' }
  if (s.error) return { variant: 'err', label: "Couldn't load" }
  return s.loaded ? { variant: 'ok', label: 'Up to date' } : null
}
