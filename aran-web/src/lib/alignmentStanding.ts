import type {
  CubeEdgeTier, CubeEvidenceEdge, DraftRunInfo, IntegrationModelBundle, ModelMapping, VersionRef,
} from '@/services/types';

/**
 * Where one accepted alignment stands against the tenant's schema (audit J4-F5): is it what the
 * active schema serves, is it waiting in a compiled draft, or has it not been compiled at all?
 * Read from the mapping's application row (V19) and the model's lineage — the server's record,
 * not a guess from counts.
 */
export type AlignmentStandingKey = 'live' | 'draft' | 'pending' | 'deferred' | 'stale';

export interface AlignmentStanding {
  key: AlignmentStandingKey;
  label: string;
  /** The sentence behind the chip — its title and its accessible description. */
  detail: string;
}

/** What a standing is measured against: three facts `GET /model` carries. */
export interface StandingContext {
  /** The tenant's ACTIVE schema version. */
  activeVersion?: VersionRef | null;
  /** The model's compiled draft still awaiting promotion; `mode` is REBUILD or INCREMENTAL. */
  draft?: Pick<DraftRunInfo, 'draftVersionId' | 'draftVersionNumber'> & Partial<Pick<DraftRunInfo, 'mode'>> | null;
  /** The version the model's last promote made ACTIVE — its baseline. */
  baselineVersionId?: string | null;
}

export function standingContext(
  bundle: Pick<IntegrationModelBundle, 'activeVersion' | 'latestDraftRun' | 'baselineVersionId'> | null | undefined,
): StandingContext {
  return {
    activeVersion: bundle?.activeVersion ?? null,
    draft: bundle?.latestDraftRun ?? null,
    baselineVersionId: bundle?.baselineVersionId ?? null,
  };
}

/**
 * An APPLIED row names the version that compiled it, which is not always the version serving it.
 * An incremental run sends only the PENDING and DEFERRED alignments and carries everything its
 * baseline had forward, so after its promote the baseline's rows still name the old version while
 * the new ACTIVE one serves them (a rebuild re-marks every row). So while the model's baseline IS
 * the ACTIVE version, every APPLIED row of its lineage is live — except one compiled into the
 * pending draft, which only that draft carries. A baseline that is no longer ACTIVE (a rollback,
 * or another project's promote) serves none of them.
 */
export function alignmentStanding(
  mapping: Pick<ModelMapping, 'application'>,
  { activeVersion, draft, baselineVersionId }: StandingContext,
): AlignmentStanding {
  const app = mapping.application;
  if (!app || app.state === 'PENDING') {
    return {
      key: 'pending',
      label: 'not compiled',
      detail: 'Accepted, but in no compiled draft yet: rebuild (or add its source incrementally) to compile it.',
    };
  }
  if (app.state === 'DEFERRED') {
    return {
      key: 'deferred',
      label: 'deferred',
      detail: `The compiler deferred it${app.reason ? `: ${app.reason}` : ''}. It is not in the schema, and compiling again unchanged defers it again.`,
    };
  }
  if (activeVersion && app.versionId === activeVersion.id) {
    return { key: 'live', label: 'live', detail: `Compiled into the active schema v${activeVersion.versionNumber}.` };
  }
  const inDraft = !!draft && app.versionId === draft.draftVersionId;
  if (activeVersion && baselineVersionId === activeVersion.id && !inDraft) {
    return {
      key: 'live',
      label: 'live',
      detail: `In the active schema v${activeVersion.versionNumber}: compiled by an earlier run of this model and carried forward by every run promoted since.`,
    };
  }
  if (draft && inDraft) {
    // A full rebuild re-marks EVERY accepted row with its draft, so "in draft" cannot tell an
    // alignment the active baseline already serves from a new one (review UA5): the row that said
    // "live in v3" is overwritten. Say what is known rather than "live once promoted".
    if (draft.mode !== 'INCREMENTAL' && activeVersion && baselineVersionId === activeVersion.id) {
      return {
        key: 'draft',
        label: `in draft v${draft.draftVersionNumber}`,
        detail: `Compiled into draft v${draft.draftVersionNumber}, a full rebuild. The active schema v${activeVersion.versionNumber} may already serve it: a rebuild re-marks every alignment it compiles, so whether v${activeVersion.versionNumber} carried this one is not recorded.`,
      };
    }
    return {
      key: 'draft',
      label: `in draft v${draft.draftVersionNumber}`,
      detail: `Compiled into draft v${draft.draftVersionNumber}; live once that draft is promoted.`,
    };
  }
  return {
    key: 'stale',
    label: 'not live',
    detail: 'Compiled into a version that is no longer active (a rollback or another promote replaced it): rebuild and promote to bring it back.',
  };
}

/**
 * Who added an accepted row, as far as the page can say it (J4-F14). Only the viewer's own id is
 * known here — a member's name needs a directory non-admins cannot read yet, the same deferral as
 * the Versions page's promoter — so another member is said to be one, never guessed at.
 */
export function addedBy(author: string | null, currentUserId: string | null | undefined): string | null {
  if (!author) return null;
  return currentUserId && author === currentUserId ? 'by you' : 'by another member';
}

/**
 * What the stepper says about the accepted work (J4-F5). "Changes to compile" means a compile would
 * change something: an alignment not compiled yet, one whose version is no longer served, or a
 * required rebuild. A DEFERRED alignment is none of these — compiling again unchanged defers it
 * again — so it is counted apart and said, instead of pinning the stepper at "changes to compile".
 */
export function compileBacklog(
  accepted: Array<Pick<ModelMapping, 'application'>>,
  ctx: StandingContext,
  rebuildRequired: boolean,
): { changesToCompile: boolean; deferredCount: number } {
  let changesToCompile = rebuildRequired;
  let deferredCount = 0;
  for (const m of accepted) {
    const key = alignmentStanding(m, ctx).key;
    if (key === 'pending' || key === 'stale') changesToCompile = true;
    if (key === 'deferred') deferredCount++;
  }
  return { changesToCompile, deferredCount };
}

/**
 * The local name of the join property a join alignment mints — `JoinLabel.propertyName()` on the
 * server, then `Names.iriFragment`: the relationship of `label|relationship|direction`, else
 * `<label>_join`. It is the `-[p]->` step OQL traverses, and the name cube-evidence reports.
 */
export function joinPropertyName(label: string | null): string {
  if (!label) return '';
  const [name, rel] = label.split('|');
  const property = rel && rel.trim() ? rel : `${name}_join`;
  return property.trim().replace(/[^A-Za-z0-9._-]/g, '_');
}

/**
 * A join alignment's trust tier in the ACTIVE catalog (audit J4-F17), from the query engine's
 * `cube-evidence` edges — `null` when the active schema has no edge for it (not compiled, not live,
 * or keyed on a composite that the evidence drops). Every accepted join starts as a HYPOTHESIS: it
 * is not a uniqueness guarantee, so per-key aggregates over it can double-count.
 */
export function joinTier(mapping: Pick<ModelMapping, 'kind' | 'label'>, edges: CubeEvidenceEdge[] | null): CubeEdgeTier | null {
  if (mapping.kind !== 'join' || !edges) return null;
  const name = joinPropertyName(mapping.label);
  if (!name) return null;
  const edge = edges.find((e) => e.propertyName === name);
  return edge ? edge.tier : null;
}

export const TIER_LABEL: Record<CubeEdgeTier, string> = {
  HYPOTHESIS: 'hypothesis',
  DECLARED: 'declared key',
  VERIFIED: 'verified key',
};

export const TIER_DETAIL: Record<CubeEdgeTier, string> = {
  HYPOTHESIS:
    'A hypothesis, not a uniqueness guarantee: nothing proves each row matches at most one row on the other side, so per-key aggregates can double-count.',
  DECLARED:
    'The source declares this as a foreign key. Trusted as declared; nothing has checked the data obeys it.',
  VERIFIED: 'The key was verified against the data.',
};
