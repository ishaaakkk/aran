import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { X, Calendar, User, Activity, Database, Table2, ArrowRight } from 'lucide-react';
import { api } from '@/services/api';
import { useProjectsStore } from '@/stores/projectsStore';
import { useAuthStore } from '@/stores/authStore';
import type { Project } from '@/services/types';
import { ProjectMembersSection } from './ProjectMembersSection';

interface ProjectDetailPanelProps {
  project: Project;
  onClose: () => void;
}

const statusVariant: Record<string, string> = {
  ACTIVE: 'text-emerald-400',
  ARCHIVED: 'text-amber-400',
  DRAFT: 'text-muted-foreground',
};

export const ProjectDetailPanel: React.FC<ProjectDetailPanelProps> = ({ project, onClose }) => {
  const navigate = useNavigate();
  const loadProjects = useProjectsStore((s) => s.load);
  const [repoCount, setRepoCount] = useState<number | null>(null);
  const [datasetCount, setDatasetCount] = useState<number | null>(null);
  // A count that could not be read shows "—" and says so; it used to read 0 (J1-F7), and one
  // failing repository silently undercounted the datasets.
  const [countsFailed, setCountsFailed] = useState(false);
  const setActiveProject = useProjectsStore((s) => s.setActiveProject);
  const myUserId = useAuthStore((s) => s.me?.userId ?? null);

  useEffect(() => {
    let cancelled = false;
    const fetchCounts = async () => {
      try {
        const repos = await api.listRepositories(project.id);
        if (cancelled) return;
        setRepoCount(repos.length);

        let dsTotal = 0;
        let anyFailed = false;
        await Promise.all(
          repos.map(async (r) => {
            try {
              const ds = await api.listDatasets(project.id, r.id);
              dsTotal += ds.length;
            } catch {
              anyFailed = true;
            }
          })
        );
        if (cancelled) return;
        setDatasetCount(anyFailed ? null : dsTotal);
        setCountsFailed(anyFailed);
      } catch {
        if (!cancelled) setCountsFailed(true);
      }
    };
    fetchCounts();
    return () => { cancelled = true; };
  }, [project.id]);

  // Going to a project's repositories or datasets makes it the working project, so the
  // choosers there preselect it (J1-F9).
  const goTo = (path: string) => {
    setActiveProject(project.id);
    onClose();
    navigate(path);
  };

  return (
    <div className="h-full min-h-0 flex flex-col">
      <div className="h-11 flex items-center justify-between px-4 border-b border-border bg-card/60 shrink-0">
        <span className="text-[10px] font-mono font-bold uppercase tracking-wider text-primary select-none">
          project · detail
        </span>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close project details"
          className="text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-5">
        <div className="grid grid-cols-2 gap-0 rounded-md border border-border/60 bg-muted/10 overflow-hidden font-mono">
          <div className="flex flex-col items-center justify-center py-3 px-2">
            <span className="text-base font-bold text-foreground tabular-nums">
              {repoCount === null ? '—' : repoCount}
            </span>
            <span className="text-[9px] uppercase tracking-wider text-muted-foreground mt-0.5">repos</span>
          </div>
          <div className="flex flex-col items-center justify-center py-3 px-2 border-l border-border/60">
            <span className="text-base font-bold text-foreground tabular-nums">
              {datasetCount === null ? '—' : datasetCount}
            </span>
            <span className="text-[9px] uppercase tracking-wider text-muted-foreground mt-0.5">datasets</span>
          </div>
        </div>
        {countsFailed && (
          <p role="status" className="text-[10.5px] text-error">
            Some counts could not be loaded — "—" is unknown, not zero.
          </p>
        )}

        <div className="flex items-start gap-3">
          <span
            className="w-3 h-3 rounded-full shrink-0 mt-0.5"
            style={{ backgroundColor: project.color || '#7c3aed' }}
          />
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-foreground leading-snug truncate">
              {project.name}
            </h3>
            {project.description && (
              <p className="text-[11px] text-muted-foreground mt-1 leading-relaxed">
                {project.description}
              </p>
            )}
          </div>
        </div>

        <span className="block h-[1px] bg-border" />

        <div className="space-y-3">
          <DetailRow
            icon={<Activity className="w-3.5 h-3.5" />}
            label="Status"
            value={
              <span className={`text-[10px] font-mono font-semibold uppercase tracking-wider ${statusVariant[project.status] ?? 'text-muted-foreground'}`}>
                {project.status}
              </span>
            }
          />
          {/* No Privacy row (UI-OD3): PUBLIC was stored and never read, so a project is visible to
              its members only — which is what the Members section below shows and changes. */}
          <DetailRow
            icon={<Calendar className="w-3.5 h-3.5" />}
            label="Created"
            value={
              <span className="text-[10.5px] font-mono text-foreground/80 tabular-nums">
                {new Date(project.createdAt).toLocaleDateString()}
              </span>
            }
          />
          <DetailRow
            icon={<User className="w-3.5 h-3.5" />}
            label="Creator"
            value={
              // select-all: the list view around this sheet is select-none, and the creator ID is
              // what an admin opens this panel to copy (J1-F18).
              <span className="text-[10.5px] font-mono text-foreground/80 break-all select-all">
                {project.createdBy === myUserId ? 'you' : project.createdBy}
              </span>
            }
          />
        </div>

        <span className="block h-[1px] bg-border" />

        {/* Removing yourself ends your view of this project: close the panel and refresh the
            list, which no longer includes it, instead of leaving controls that would 404. */}
        <ProjectMembersSection
          project={project}
          onSelfRemoved={() => {
            void loadProjects();
            onClose();
          }}
        />

      </div>

      <div className="shrink-0 border-t border-border p-3 flex items-center gap-2">
        <button
          type="button"
          onClick={() => goTo('/repositories')}
          className="flex-1 h-8 flex items-center justify-center gap-1.5 text-[10px] font-mono font-semibold uppercase tracking-wider text-foreground/80 hover:text-primary border border-border hover:border-primary/30 rounded-md transition-all cursor-pointer"
        >
          <Database className="w-3 h-3" />
          Repos
          <ArrowRight className="w-3 h-3" />
        </button>
        <button
          type="button"
          onClick={() => goTo('/datasets')}
          className="flex-1 h-8 flex items-center justify-center gap-1.5 text-[10px] font-mono font-semibold uppercase tracking-wider text-foreground/80 hover:text-primary border border-border hover:border-primary/30 rounded-md transition-all cursor-pointer"
        >
          <Table2 className="w-3 h-3" />
          Datasets
          <ArrowRight className="w-3 h-3" />
        </button>
        <button
          type="button"
          onClick={onClose}
          className="h-8 px-3 flex items-center justify-center text-[10px] font-mono text-muted-foreground hover:text-foreground border border-border rounded-md transition-all cursor-pointer"
        >
          <X className="w-3 h-3" />
        </button>
      </div>
    </div>
  );
};

const DetailRow: React.FC<{ icon: React.ReactNode; label: string; value: React.ReactNode }> = ({
  icon,
  label,
  value,
}) => (
  <div className="flex items-center gap-2.5">
    <span className="text-muted-foreground shrink-0">{icon}</span>
    <span className="text-[9px] font-mono uppercase tracking-wider text-muted-foreground w-16 shrink-0">
      {label}
    </span>
    {value}
  </div>
);

export default ProjectDetailPanel;
