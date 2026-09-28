import React, { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import useProjectsStore from '@/stores/projectsStore';
import { useAuthStore } from '@/stores/authStore';
import { ErrorNotice } from '@/components/ErrorNotice';
import { DataTable, Column } from '@/components/DataTable';
import { ProjectDetailPanel } from '@/components/ProjectDetailPanel';
import { Dialog } from '@/components/ui/Dialog';
import { StatusDot } from '@/components/StatusDot';
import { Search, Plus, X, ShieldAlert, Loader2, Folder } from 'lucide-react';
import type { Project } from '@/services/types';
import { errorMessage, licenceLimitMessage } from '@/lib/errors';
import { ownerLabel } from '@/lib/projectOwner';

export const ProjectListView: React.FC = () => {
  // Selected field by field: `load()` fires several separate `set()` calls, and a
  // whole-store subscription re-renders this whole view on each one.
  const projects = useProjectsStore((s) => s.projects);
  const projectsStatus = useProjectsStore((s) => s.status);
  const projectsLoadError = useProjectsStore((s) => s.loadError);
  const loadProjects = useProjectsStore((s) => s.load);
  const createProject = useProjectsStore((s) => s.create);
  const myUserId = useAuthStore((s) => s.me?.userId ?? null);
  const location = useLocation();
  const navigate = useNavigate();

  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [selectedProject, setSelectedProject] = useState<Project | null>(null);

  const [search, setSearch] = useState('');

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [color, setColor] = useState('#8B5CF6');
  const [formError, setFormError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    loadProjects();
    // `loadProjects` is a stable zustand action, so this still runs once on mount
    // — see DashboardView.tsx's identical effect.
  }, [loadProjects]);

  const colorOptions = ['#8B5CF6', '#10B981', '#3B82F6', '#F59E0B', '#EF4444'];

  const filteredProjects = useMemo(() => {
    const list = projects || [];
    // No status filter (J1-F10): every project is ACTIVE — nothing writes another status — so
    // REVIEW and DRAFT never matched anything and ACTIVE matched everything.
    return list.filter((p) =>
      p.name.toLowerCase().includes(search.toLowerCase()) ||
      p.description.toLowerCase().includes(search.toLowerCase()));
  }, [projects, search]);

  const rows = useMemo(() => {
    return filteredProjects.map((p, index) => {
      const d = p.createdAt ? new Date(p.createdAt) : new Date();
      const dateStr = d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
      return {
        ...p,
        idx: String(index + 1).padStart(2, '0'),
        created: dateStr,
        statusStr: (p.status || 'ACTIVE').toLowerCase(),
        owner: ownerLabel(p.createdBy, myUserId),
      };
    });
  }, [filteredProjects, myUserId]);

  // No privacy column (UI-OD3): PUBLIC was stored and never read, so every project is visible to
  // its members only, and a column saying otherwise was the claim audit J1-F4 caught.
  const columns: Column[] = [
    { key: 'idx',       label: '',         width: '28px',  num: true, mono: true },
    { key: 'name',      label: 'project',  mono: true },
    { key: 'statusStr', label: 'status',   width: '110px' },
    { key: 'owner',     label: 'owner',    width: '110px', mono: true },
    { key: 'created',   label: 'created',  width: '90px',  align: 'right', mono: true },
  ];

  const statusVar = (s: string): 'ok' | 'warn' | 'idle' => {
    if (s === 'active') return 'ok';
    if (s === 'review') return 'warn';
    return 'idle';
  };

  const handleRowClick = (row: { id: string }) => {
    const proj = projects.find((p) => p.id === row.id) || null;
    setSelectedProject(proj);
  };

  const handleCreateOpen = () => {
    setName('');
    setDescription('');
    setColor(colorOptions[0]);
    setFormError(null);
    setIsCreateOpen(true);
  };

  // Arriving with an intent — the dashboard's "Create a project" step, or a dashboard row that
  // opens its project (J1-F2, J1-F10). Router state, not the URL, so a reload does not repeat
  // it; the state is cleared once acted on.
  const intent = location.state as { create?: boolean; open?: string } | null;
  useEffect(() => {
    if (intent?.create) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      handleCreateOpen();
      navigate('.', { replace: true, state: null });
    }
    // Once per arrival; handleCreateOpen is re-created every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intent?.create]);
  useEffect(() => {
    if (!intent?.open || projectsStatus !== 'ready') return;
    const target = projects.find((p) => p.id === intent.open) ?? null;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedProject(target);
    navigate('.', { replace: true, state: null });
  }, [intent?.open, projectsStatus, projects, navigate]);

  const handleCreateSave = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName) {
      setFormError('Project name is required.');
      return;
    }

    setCreating(true);
    setFormError(null);
    try {
      const res = await createProject(trimmedName, description.trim(), color);
      if (res) {
        setIsCreateOpen(false);
      } else {
        setFormError(projectsLoadError ? errorMessage(projectsLoadError, 'Failed to create project.') : 'Failed to create project.');
      }
    } catch (err: unknown) {
      // The server's own sentence (J1-F6) — it used to be a stale closure's fixed "Failed to
      // create project." — and a 402 said with its numbers and the way on.
      setFormError(licenceLimitMessage(err) ?? errorMessage(err, 'Failed to create project.'));
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="flex-grow flex flex-col p-6 gap-6 min-h-0 relative select-none">
      {/* Toolbar */}
      <div id="tour-projects-header" className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-4 shrink-0">
        <div className="relative flex-1 max-w-sm">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            placeholder="Search projects..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full h-8 rounded-md border border-border bg-card pl-9 pr-16 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary transition-all"
          />
          <span className="absolute right-3 top-1/2 -translate-y-1/2 font-mono text-[10px] text-muted-foreground/70 tabular-nums select-none">
            {rows.length}/{(projects || []).length}
          </span>
        </div>

        <div className="flex items-center gap-1.5 font-mono text-[10.5px]">
          <button
            id="tour-projects-new"
            type="button"
            onClick={handleCreateOpen}
            data-testid="projects-new-button"
            className="ml-1 inline-flex items-center gap-1.5 h-8 px-4 rounded-md bg-primary font-semibold text-primary-foreground hover:bg-primary/90 transition-colors uppercase tracking-wider"
          >
            <Plus size={12} />
            new project
          </button>
        </div>
      </div>

      {/* Table */}
      <div id="tour-projects-list" className="flex-grow overflow-y-auto">
        <DataTable
          command="Projects"
          columns={columns}
          rows={rows}
          onRowClick={handleRowClick}
          getRowTestId={(row) => `projects-row-${row.name}`}
          emptyMessage={
            projectsStatus === 'error' ? (
              // A failed load is not "No projects found" (J1-F7, FRONTEND-6): that invited a
              // duplicate of a project that exists.
              <ErrorNotice
                testId="projects-load-error"
                title="Couldn't load your projects"
                error={projectsLoadError}
                onRetry={() => void loadProjects()}
              />
            ) : projectsStatus !== 'ready' ? (
              <div className="flex flex-col items-center gap-3 text-muted-foreground">
                <Loader2 className="animate-spin w-8 h-8 text-primary/50" />
                <span className="font-mono text-xs uppercase tracking-widest">Loading Workspaces...</span>
              </div>
            ) : (
              <div className="flex flex-col items-center gap-4 py-8">
                <div className="w-16 h-16 rounded-full bg-muted/50 border border-border flex items-center justify-center mb-2">
                  <Folder className="w-8 h-8 text-muted-foreground/60" />
                </div>
                <div className="text-center space-y-1.5">
                  <h3 className="text-base font-semibold text-foreground tracking-tight">No projects found</h3>
                  <p className="text-xs text-muted-foreground max-w-sm">
                    Projects are isolated workspaces where you define datasets, alignments, and constraints to build your integration models.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={handleCreateOpen}
                  className="mt-4 inline-flex items-center gap-2 h-9 px-5 rounded-md bg-primary font-semibold text-primary-foreground hover:bg-primary/90 transition-colors uppercase tracking-wider text-[11px] shadow-sm"
                >
                  <Plus size={14} />
                  Create Your First Project
                </button>
              </div>
            )
          }
          renderCell={{
            // The full id on hover for "someone else" (J1-F10) — the detail panel has it selectable.
            owner: (row) => (
              <span title={row.createdBy && row.owner !== 'you' ? row.createdBy : undefined}>{row.owner}</span>
            ),
            name: (row) => (
              <span className="inline-flex items-center gap-2">
                <span
                  className="w-1.5 h-1.5 rounded-full shrink-0"
                  style={{ backgroundColor: row.color || 'var(--primary)' }}
                />
                <span>{row.name}</span>
              </span>
            ),
            statusStr: (row) => (
              <StatusDot variant={statusVar(row.statusStr)} label={row.statusStr} />
            ),
          }}
        />
      </div>

      {/* Detail panel */}
      {/* J1-F18: the shared Dialog as a sheet — a click inside no longer closes it, Escape
          does, focus is trapped and returned, and it is announced as a named dialog. */}
      {selectedProject && (
        <Dialog
          open
          variant="sheet"
          onClose={() => setSelectedProject(null)}
          label={`Project ${selectedProject.name}`}
        >
          <ProjectDetailPanel
            project={selectedProject}
            onClose={() => setSelectedProject(null)}
          />
        </Dialog>
      )}

      {/* Create modal */}
      {isCreateOpen && (
        <CreateProjectModal
          name={name}
          onName={setName}
          description={description}
          onDescription={setDescription}
          color={color}
          colorOptions={colorOptions}
          onColor={setColor}
          formError={formError}
          creating={creating}
          onSubmit={handleCreateSave}
          onClose={() => setIsCreateOpen(false)}
        />
      )}
    </div>
  );
};

/**
 * The create form. Split out of the view so its content can be rendered by a test — the modal is
 * otherwise only reachable by a click, which `renderToStaticMarkup` cannot make. Markup and testids
 * are unchanged; `ProjectsPage` drives them.
 */
export const CreateProjectModal: React.FC<{
  name: string;
  onName: (v: string) => void;
  description: string;
  onDescription: (v: string) => void;
  color: string;
  colorOptions: string[];
  onColor: (c: string) => void;
  formError: string | null;
  creating: boolean;
  onSubmit: (e: React.FormEvent) => void;
  onClose: () => void;
}> = ({
  name, onName, description, onDescription, color, colorOptions, onColor, formError, creating,
  onSubmit, onClose,
}) => (
  <div
    className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
    onClick={onClose}
    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onClose(); }}
    role="button"
    tabIndex={0}
  >
    <form
      onSubmit={onSubmit}
      className="relative w-full max-w-md mx-4 rounded-md border border-border bg-card shadow-2xl overflow-hidden text-foreground"
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') e.stopPropagation(); }}
      role="presentation"
    >
      <div className="flex items-center justify-between border-b border-border bg-muted/20 px-5 py-3">
        <h2 className="font-mono text-xs uppercase tracking-widest text-foreground font-semibold flex items-center gap-1.5">
          <span className="text-primary font-bold">#</span>
          create project
        </h2>
        <button
          type="button"
          onClick={onClose}
          disabled={creating}
          className="text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
        >
          <X size={16} />
        </button>
      </div>

      <div className="px-5 py-4 space-y-4">
        <div className="space-y-1">
          <label htmlFor="project-name" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
            project name
          </label>
          <input
            id="project-name"
            type="text"
            value={name}
            onChange={(e) => onName(e.target.value)}
            disabled={creating}
            placeholder="e.g. Health Records Schema"
            data-testid="projects-name-input"
            className="w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
          />
        </div>

        <div className="space-y-1">
          <label htmlFor="project-description" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
            description
          </label>
          <textarea
            id="project-description"
            value={description}
            onChange={(e) => onDescription(e.target.value)}
            disabled={creating}
            rows={3}
            placeholder="Summarize project integration goals..."
            data-testid="projects-description-input"
            className="w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-sm text-foreground resize-none focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
          />
        </div>

        {/* UI-OD3: no visibility choice. PUBLIC was stored and never read — a "public" project was
            as invisible to the team as any other — so the option left and the form says what is
            true instead. */}
        <p className="text-[11px] leading-relaxed text-muted-foreground" data-testid="projects-visibility-note">
          Only members see a project. You are its first member; add colleagues from the project's
          Members section.
        </p>

        <div className="space-y-1.5">
          <div className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
            identity highlight
          </div>
          <div className="flex gap-2.5">
            {colorOptions.map((c) => (
              <button
                key={c}
                type="button"
                onClick={() => onColor(c)}
                aria-label={`Highlight colour ${c}`}
                aria-pressed={color === c}
                className={`w-6 h-6 rounded-full border-2 transition-all shrink-0 ${
                  color === c
                    ? 'border-foreground scale-110 shadow-lg'
                    : 'border-transparent scale-100 hover:scale-105'
                }`}
                style={{ backgroundColor: c }}
              />
            ))}
          </div>
        </div>

        {formError && (
          <div className="flex items-center gap-2 font-mono text-xs text-rose-400">
            <ShieldAlert size={14} className="shrink-0" />
            <span>{formError}</span>
          </div>
        )}
      </div>

      <div className="flex items-center justify-end gap-2 border-t border-border px-5 py-3 bg-muted/5">
        <button
          type="button"
          onClick={onClose}
          disabled={creating}
          className="rounded-md border border-border px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-muted-foreground hover:text-foreground hover:border-foreground transition-colors disabled:opacity-40"
        >
          cancel
        </button>
        <button
          type="submit"
          disabled={creating || !name.trim()}
          data-testid="projects-submit"
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-60"
        >
          {creating && <Loader2 size={12} className="animate-spin" />}
          create
        </button>
      </div>
    </form>
  </div>
);

export default ProjectListView;
