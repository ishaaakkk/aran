import React, { useState, useEffect } from 'react';
import { Settings, X } from 'lucide-react';
import { useAuthStore } from '../stores/authStore';
import { errorMessage } from '@/lib/errors';
import { Dialog } from '@/components/ui/Dialog';

interface EditProfileDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved?: () => void;
}

export const EditProfileDialog: React.FC<EditProfileDialogProps> = ({ isOpen, onClose, onSaved }) => {
  // Selected field by field: this dialog is mounted by IconRail on every page,
  // so a whole-store subscription re-renders it on every unrelated auth action.
  const me = useAuthStore((s) => s.me);
  const updateMe = useAuthStore((s) => s.updateMe);
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (isOpen && me) {
      // Re-seeds the editable fields from the store every time the dialog opens —
      // there is no render-time equivalent, since these are meant to diverge from
      // `me` as the user types.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setDisplayName(me.displayName || '');
      setEmail(me.email || '');
      setError('');
    }
  }, [isOpen, me]);

  if (!isOpen) return null;

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!displayName.trim()) {
      setError('Display Name is required');
      return;
    }
    if (!email.trim() || !/.+@.+\..+/.test(email)) {
      setError('Provide a valid email address');
      return;
    }

    setSubmitting(true);
    try {
      updateMe({ displayName, email });
      if (onSaved) onSaved();
      onClose();
    } catch (err: unknown) {
      setError(errorMessage(err, 'Failed to update profile'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open onClose={onClose} label="Edit user profile" maxWidth="max-w-[400px]" className="glass">
        <header className="h-11 flex items-center px-4 justify-between border-b border-border bg-card/60">
          <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider font-mono text-primary select-none">
            <Settings className="w-4 h-4" />
            <span>edit · user profile</span>
          </div>
          <button 
            type="button"
            onClick={onClose} 
            className="text-muted-foreground hover:text-foreground transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </header>

        <form onSubmit={handleSave} className="p-4 space-y-4">
          {error && (
            <div className="p-2.5 bg-rose-500/10 border border-rose-500/30 rounded text-rose-400 text-xs font-mono">
              {error}
            </div>
          )}

          <div className="space-y-1">
            <label htmlFor="displayName" className="block text-[9px] uppercase tracking-wider font-mono text-muted-foreground">
              Display Name
            </label>
            <input
              id="displayName"
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="Jane Doe"
              className="w-full h-9 px-3 text-xs bg-card border border-border rounded focus:outline-none focus:border-primary/50 transition-colors"
            />
          </div>

          <div className="space-y-1">
            <label htmlFor="email" className="block text-[9px] uppercase tracking-wider font-mono text-muted-foreground">
              Email Address
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="jane.doe@example.com"
              className="w-full h-9 px-3 text-xs bg-card border border-border rounded focus:outline-none focus:border-primary/50 transition-colors"
            />
          </div>

          {/* Honesty over silence: updateMe persists only a browser-local override — the
              backend identity (and what teammates see) is unchanged. A "Save Changes"
              that quietly saved nothing durable erodes trust (redesign brief, entry HIGH #2). */}
          <p className="text-[11px] font-mono text-muted-foreground leading-relaxed border border-border/60 bg-muted/20 rounded p-2.5">
            Saved on this browser only — your sign-in identity is managed by your
            organization and is not changed here.
          </p>

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="h-9 px-4 text-xs font-medium rounded text-muted-foreground hover:text-foreground transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="h-9 px-4 text-xs font-mono font-semibold uppercase tracking-wider bg-primary hover:bg-transparent border border-primary text-primary-foreground hover:text-primary rounded transition-all duration-150 disabled:opacity-50"
            >
              {submitting ? 'Saving...' : 'Save Changes'}
            </button>
          </div>
        </form>
    </Dialog>
  );
};

export default EditProfileDialog;
