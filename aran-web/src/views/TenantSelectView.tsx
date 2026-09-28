import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useAuthStore from '@/stores/authStore';
import { api, ApiError } from '@/services/api';
import type { LicenseSummary, TenantMembership } from '@/services/types';
import { Shield, Sparkles, ArrowRight, Copy, Check } from 'lucide-react';
import { errorMessage } from '@/lib/errors';
import { copyText } from '@/lib/clipboard';
import { isUnlimited, planLabel, quotaLabel } from '@/lib/licenseDisplay';

/**
 * Account › Organization. A user belongs to exactly one organization (V23,
 * `uq_membership_user`), so this is an account fact — name, role, a copyable ID, the licence —
 * not a chooser. The page used to read "Your organizations — Select or review…" with an
 * "Active Session" chip, and owners looked for a switcher that cannot exist (UI audit J1-F19 =
 * J7-F19).
 */

const pct = (current: number, max: number) => {
  if (max <= 0) return 0;
  return Math.min(100, Math.round((current / max) * 100));
};

const planBadgeColor = (plan: string) => {
  const p = plan.toUpperCase();
  if (p === 'ENTERPRISE') return 'bg-amber-500/15 text-amber-400 border-amber-500/30';
  if (p === 'PRO') return 'bg-sky-500/15 text-sky-400 border-sky-500/30';
  return 'bg-neutral-500/15 text-muted-foreground border-border';
};

/** Name, role and a copyable ID for the one organization the user belongs to. */
export const OrganizationSummary: React.FC<{ tenant: TenantMembership }> = ({ tenant }) => {
  const [copied, setCopied] = useState<boolean | null>(null);
  const isOwner = tenant.role.toUpperCase() === 'OWNER';

  const copyId = async () => {
    setCopied(await copyText(tenant.tenantId));
    setTimeout(() => setCopied(null), 2500);
  };

  return (
    <div className="flex items-start justify-between gap-4 border border-border/60 rounded p-4 bg-muted/10">
      <div className="space-y-1.5 min-w-0">
        <div className="text-sm font-bold text-foreground">{tenant.name}</div>
        <div className="flex items-center gap-2 font-mono text-[11px] text-muted-foreground">
          <span>ID</span>
          <code className="text-foreground/85 break-all select-all">{tenant.tenantId}</code>
          <button
            type="button"
            onClick={() => void copyId()}
            aria-label="Copy organization ID"
            title={copied === false ? 'Couldn’t copy — select the ID instead' : 'Copy organization ID'}
            className="p-0.5 text-muted-foreground hover:text-foreground"
          >
            {copied === true ? <Check className="w-3.5 h-3.5 text-success" /> : <Copy className="w-3.5 h-3.5" />}
          </button>
          {copied === false && <span role="status">couldn’t copy — select it</span>}
        </div>
      </div>
      <span
        className={`font-mono text-[10px] uppercase tracking-wider font-extrabold border px-2 py-0.5 rounded-sm shrink-0 ${
          isOwner
            ? 'bg-amber-500/10 text-amber-400 border-amber-500/35'
            : 'bg-indigo-500/10 text-indigo-400 border-indigo-500/35'
        }`}
      >
        {tenant.role}
      </span>
    </div>
  );
};

const Quota: React.FC<{ label: string; current: number; max: number }> = ({ label, current, max }) => (
  <div className="space-y-1.5">
    <div className="flex justify-between text-muted-foreground text-[11px]">
      <span>{label}</span>
      <span className="text-foreground">{quotaLabel(current, max)}</span>
    </div>
    {!isUnlimited(max) && (
      <div className="h-1.5 w-full bg-border/40 rounded-full overflow-hidden">
        <div
          className={`h-full transition-all duration-300 ${pct(current, max) >= 80 ? 'bg-amber-500' : 'bg-primary'}`}
          style={{ width: `${pct(current, max)}%` }}
        />
      </div>
    )}
  </div>
);

/** The organization's licence: plan, quotas, features, expiry. */
export const LicensePanel: React.FC<{ license: LicenseSummary }> = ({ license }) => {
  const plan = planLabel(license);
  return (
    <section className="border border-border/80 bg-muted/15 rounded p-5 space-y-5">
      <header className="flex items-center gap-2 border-b border-border/40 pb-3">
        <Shield size={16} className="text-primary shrink-0" />
        <h3 className="font-mono text-xs uppercase tracking-widest text-foreground font-semibold">
          License
        </h3>
        <span className={`font-mono text-[10px] tracking-wide font-bold border px-2 py-0.5 rounded-sm ml-auto ${planBadgeColor(license.plan)}`}>
          {plan}
        </span>
        {!license.mutationsAllowed && (
          <span
            title="License is suspended or expired. Reads still work."
            className="font-mono text-[10px] uppercase tracking-widest font-bold px-2 py-0.5 rounded bg-destructive text-destructive-foreground border border-error/40"
          >
            Read-only
          </span>
        )}
      </header>

      <div className="space-y-4 font-mono text-xs">
        <Quota label="Users" current={license.currentUsers} max={license.maxUsers} />
        <Quota label="Projects" current={license.currentProjects} max={license.maxProjects} />
      </div>

      {license.features.length > 0 && (
        <div className="space-y-2 pt-2 border-t border-border/40">
          <div className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
            Features
          </div>
          <div className="flex flex-wrap gap-1.5">
            {license.features.map((f) => (
              <span
                key={f}
                className="font-mono text-[10px] px-2 py-0.5 rounded-sm bg-muted/40 border border-border/80 text-foreground/80 flex items-center gap-1"
              >
                <Sparkles size={8} className="text-primary shrink-0" />
                {f.replace(/_/g, ' ').toLowerCase()}
              </span>
            ))}
          </div>
        </div>
      )}

      {license.expiresAt && (
        <p className="font-mono text-[11px] text-muted-foreground border-t border-border/40 pt-2.5">
          Expires{' '}
          <span className="text-foreground/80 font-medium">
            {new Date(license.expiresAt).toLocaleDateString()}
          </span>
        </p>
      )}
    </section>
  );
};

export const TenantSelectView: React.FC = () => {
  // Selected field by field: subscribing to the whole store re-renders this view
  // on every unrelated auth action.
  const tenants = useAuthStore((s) => s.tenants);
  const activeTenantId = useAuthStore((s) => s.activeTenantId);
  const navigate = useNavigate();

  const [license, setLicense] = useState<LicenseSummary | null>(null);
  const [licenseError, setLicenseError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const fetchLicense = async () => {
      if (!activeTenantId) return;
      setLoading(true);
      setLicenseError(null);
      try {
        const res = await api.getLicense(activeTenantId);
        setLicense(res);
      } catch (e: unknown) {
        setLicenseError(e instanceof ApiError ? errorMessage(e) : 'failed to load license');
      } finally {
        setLoading(false);
      }
    };

    fetchLicense();
  }, [activeTenantId]);

  const tenant = tenants.find((t) => t.tenantId === activeTenantId) ?? tenants[0] ?? null;

  return (
    <div className="flex-grow max-w-4xl mx-auto w-full px-6 py-10 font-sans space-y-8 animate-in">
      <div className="space-y-1">
        <h2 className="text-xl font-bold tracking-tight text-foreground">Organization</h2>
        <p className="text-xs text-muted-foreground">
          Everything in your workspace belongs to this organization. You belong to exactly one —
          there is nothing to switch.
        </p>
      </div>

      {!tenant ? (
        <div className="border border-border/80 bg-muted/10 p-8 rounded text-center text-xs font-mono text-muted-foreground">
          You do not belong to an organization yet. Ask an organization owner to add you.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-[1.2fr_1fr] gap-8 items-start">
          <div className="space-y-4">
            <OrganizationSummary tenant={tenant} />
            <div className="flex justify-end pt-2">
              <button
                type="button"
                onClick={() => navigate('/projects')}
                className="inline-flex items-center gap-2 rounded bg-primary px-5 py-2 font-mono text-xs uppercase tracking-widest text-primary-foreground font-semibold hover:bg-primary/95 transition-colors"
              >
                Continue to projects
                <ArrowRight size={12} />
              </button>
            </div>
          </div>

          <div className="space-y-4">
            {loading ? (
              <div role="status" className="border border-border/80 bg-muted/15 p-8 rounded text-center text-xs font-mono text-muted-foreground">
                Loading the license…
              </div>
            ) : license ? (
              <LicensePanel license={license} />
            ) : licenseError ? (
              <div role="alert" className="border border-border/80 bg-muted/10 p-5 rounded text-xs font-mono text-muted-foreground">
                Could not load license details: {licenseError}
              </div>
            ) : null}
          </div>
        </div>
      )}
    </div>
  );
};

export default TenantSelectView;
