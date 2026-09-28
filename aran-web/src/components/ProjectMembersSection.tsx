import React, { useCallback, useEffect, useState } from 'react';
import { Loader2, UserMinus, UserPlus, Users } from 'lucide-react';
import { api } from '@/services/api';
import { useAuthStore } from '@/stores/authStore';
import { errorMessage } from '@/lib/errors';
import { addCandidates, memberLines, removeThenFollowUp, type ProjectMemberLine } from '@/lib/projectMembers';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import type { Project, ProjectMember, TenantMember } from '@/services/types';

/**
 * Who can see a project, and — for an owner — the controls to change it (audit J1-F4).
 *
 * A project is visible only to its members; `PUBLIC` was stored and never read, so it left the UI
 * (UI-OD3) and this section is the one way to share a project. The backend endpoints existed with
 * no UI over them: an admin could invite Ana to the organization and then had no way to let her
 * see anything. Removing someone asks first and names what it does; the project's last member
 * cannot be removed, since nobody — owners included — could reach the project afterwards.
 *
 * Removing yourself ends your view of the project, so the section hands that back to its host
 * (`onSelfRemoved`) rather than reloading a list it can no longer read.
 */
export const ProjectMembersSection: React.FC<{ project: Project; onSelfRemoved?: () => void }> = ({
  project, onSelfRemoved,
}) => {
  const me = useAuthStore((s) => s.me);
  const canManage = useAuthStore((s) => s.activeTenant?.role === 'OWNER');

  const [projectMembers, setProjectMembers] = useState<ProjectMember[] | null>(null);
  /** Null when the organization's list could not be read — distinct from an empty one. */
  const [tenantMembers, setTenantMembers] = useState<TenantMember[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState<ProjectMemberLine | null>(null);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const [pm, tm] = await Promise.all([
        api.listProjectMembers(project.id),
        // Names only: a failure here leaves ids on screen, which is still the truth — but it is
        // a failure, kept as null, never an empty organization.
        api.listTenantMembers().catch(() => null),
      ]);
      setProjectMembers(pm);
      setTenantMembers(tm);
    } catch (e) {
      setLoadError(errorMessage(e, 'Could not load the members of this project.'));
    }
  }, [project.id]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  const add = async (userId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      await api.addProjectMember(project.id, userId);
      await load();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!removing) return;
    setBusy(true);
    setActionError(null);
    try {
      await removeThenFollowUp(removing, {
        remove: (userId) => api.removeProjectMember(project.id, userId),
        reload: load,
        onSelfRemoved,
      });
      setRemoving(null);
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <ProjectMembersList
        projectName={project.name}
        lines={projectMembers === null ? null : memberLines(projectMembers, tenantMembers, me?.userId ?? null)}
        candidates={projectMembers === null || tenantMembers === null ? [] : addCandidates(tenantMembers, projectMembers)}
        namesUnavailable={projectMembers !== null && tenantMembers === null}
        canManage={canManage}
        busy={busy}
        loadError={loadError}
        actionError={removing ? null : actionError}
        onRetry={() => void load()}
        onAdd={(userId) => void add(userId)}
        onRemove={(line) => { setActionError(null); setRemoving(line); }}
      />
      {removing && (
        <ConfirmDialog
          open
          title={`Remove ${removing.name} from ${project.name}?`}
          consequences={[
            removing.isSelf
              ? 'You stop seeing this project and everything in it as soon as you confirm.'
              : `${removing.name} stops seeing this project and everything in it immediately.`,
            'Its datasets, alignments and products are kept; nothing in the project is deleted.',
            'An organization owner can add them back at any time.',
            'The change is recorded in Activity.',
          ]}
          confirmLabel="Remove from project"
          busy={busy}
          error={actionError}
          onConfirm={() => void remove()}
          onCancel={() => setRemoving(null)}
          testId="project-member-remove"
        />
      )}
    </>
  );
};

/** The presentational half — exported for the render test. */
export const ProjectMembersList: React.FC<{
  projectName: string;
  lines: ProjectMemberLine[] | null;
  candidates: TenantMember[];
  /** The organization's member list could not be read: names are ids, and Add cannot offer anyone. */
  namesUnavailable?: boolean;
  canManage: boolean;
  busy: boolean;
  loadError: string | null;
  actionError: string | null;
  onRetry: () => void;
  onAdd: (userId: string) => void;
  onRemove: (line: ProjectMemberLine) => void;
}> = ({ projectName, lines, candidates, namesUnavailable = false, canManage, busy, loadError, actionError, onRetry, onAdd, onRemove }) => {
  const [selected, setSelected] = useState('');
  const onlyMember = (lines?.length ?? 0) <= 1;

  return (
    <section className="space-y-2" aria-labelledby="project-members-heading" data-testid="project-members">
      <h4 id="project-members-heading" className="flex items-center gap-1.5 text-[10px] font-mono uppercase tracking-wider text-muted-foreground">
        <Users className="w-3.5 h-3.5" aria-hidden="true" />
        Members{lines ? ` (${lines.length})` : ''}
      </h4>

      {/* A failed read is said whether or not an older list is on screen: a reload that fails
          after Add or Remove must not leave the old list reading as the result. */}
      {loadError && (
        <div role="alert" className="rounded-md border border-error/40 bg-error/10 p-2 text-[11px] text-error space-y-1.5">
          <p>
            {loadError}
            {lines !== null && ' The list below is from before, and may not show the latest change.'}
          </p>
          <button type="button" onClick={onRetry} className="underline underline-offset-2 font-medium">
            Retry
          </button>
        </div>
      )}
      {namesUnavailable && !loadError && (
        <div role="alert" className="rounded-md border border-border bg-muted/40 p-2 text-[11px] text-muted-foreground space-y-1.5">
          <p>Could not load the organization’s members, so members are shown by id and nobody can be added.</p>
          <button type="button" onClick={onRetry} className="underline underline-offset-2 font-medium text-foreground">
            Retry
          </button>
        </div>
      )}
      {lines === null && !loadError && (
        <p role="status" className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <Loader2 className="w-3 h-3 animate-spin" aria-hidden="true" /> Loading members…
        </p>
      )}

      {lines && (
        <ul className="space-y-1">
          {lines.map((line) => (
            <li key={line.userId} className="flex items-center gap-2 text-[11px]" data-testid={`project-member-${line.userId}`}>
              <span className="min-w-0 flex-1 truncate">
                <span className="text-foreground">{line.name}</span>
                {line.isSelf && <span className="text-muted-foreground"> (you)</span>}
                {line.email && <span className="block truncate text-[10px] text-muted-foreground">{line.email}</span>}
                {line.known === false && <span className="block text-[10px] text-muted-foreground">no longer in the organization</span>}
              </span>
              {canManage && (
                <button
                  type="button"
                  onClick={() => onRemove(line)}
                  disabled={busy || onlyMember}
                  title={onlyMember ? 'The last member cannot be removed — nobody could see the project afterwards' : `Remove ${line.name}`}
                  aria-label={`Remove ${line.name} from ${projectName}`}
                  className="shrink-0 rounded border border-border p-1 text-muted-foreground hover:text-error hover:border-error/40 disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <UserMinus className="w-3.5 h-3.5" aria-hidden="true" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {lines && canManage && (
        <form
          className="flex items-center gap-1.5"
          onSubmit={(e) => {
            e.preventDefault();
            if (selected) {
              onAdd(selected);
              setSelected('');
            }
          }}
        >
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            disabled={busy || candidates.length === 0}
            aria-label={`Add a member to ${projectName}`}
            className="min-w-0 flex-1 h-7 rounded-md border border-border bg-background px-1.5 text-[11px] text-foreground"
          >
            <option value="">
              {namesUnavailable ? 'Could not load the organization’s members'
                : candidates.length === 0 ? 'Everyone in the organization is a member' : 'Add someone…'}
            </option>
            {candidates.map((m) => (
              <option key={m.userId} value={m.userId}>{m.displayName} — {m.email}</option>
            ))}
          </select>
          <button
            type="submit"
            disabled={busy || !selected}
            className="inline-flex h-7 items-center gap-1 rounded-md border border-border px-2 text-[11px] font-medium text-foreground hover:border-primary/40 disabled:opacity-40"
          >
            <UserPlus className="w-3.5 h-3.5" aria-hidden="true" /> Add
          </button>
        </form>
      )}

      {actionError && <p role="alert" className="text-[11px] text-error">{actionError}</p>}

      <p className="text-[10.5px] leading-relaxed text-muted-foreground">
        {canManage
          ? 'Only members see a project. Someone new to the organization sees no project until they are added to one.'
          : 'Only members see a project. An organization owner can change who is in it.'}
      </p>
    </section>
  );
};

export default ProjectMembersSection;
