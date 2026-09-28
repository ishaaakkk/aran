import React from 'react';
import { localName } from '@/lib/iri';
import type { SchemaDiff } from '@/services/types';

/**
 * A class/property-level schema diff, older → newer. One rendering for the Versions page's
 * comparison and the promote/rollback confirmations, so the list a user is asked to confirm is
 * the list they would see on the Versions page (and the list the promote guard refuses on).
 *
 * `removedNote` is said under the removed classes — what losing them costs in this context.
 */
interface SchemaDiffListProps {
  diff: SchemaDiff;
  removedNote?: string;
  testId?: string;
}

export const SchemaDiffList: React.FC<SchemaDiffListProps> = ({ diff, removedNote, testId }) => {
  if (diff.addedClasses.length === 0 && diff.removedClasses.length === 0 && diff.changedClasses.length === 0) {
    return (
      <div data-testid={testId} className="text-[11px] font-mono text-muted-foreground">
        No differences — the two versions describe the same classes, properties and wrappers.
      </div>
    );
  }
  return (
    <div data-testid={testId} className="flex flex-col gap-3 text-[11px] font-mono">
      {diff.removedClasses.length > 0 && (
        <div>
          <div className="text-error font-semibold mb-1">− {diff.removedClasses.length} removed class{diff.removedClasses.length === 1 ? '' : 'es'}</div>
          {removedNote && <div className="text-error/90 font-sans text-[11.5px] mb-1 leading-relaxed">{removedNote}</div>}
          <ul data-testid={testId ? `${testId}-removed` : undefined} className="space-y-0.5">
            {diff.removedClasses.map((c) => (
              <li key={c} className="text-error pl-3" title={c}>− {localName(c)}</li>
            ))}
          </ul>
        </div>
      )}
      {diff.addedClasses.length > 0 && (
        <div>
          <div className="text-success font-semibold mb-1">+ {diff.addedClasses.length} added class{diff.addedClasses.length === 1 ? '' : 'es'}</div>
          <ul className="space-y-0.5">
            {diff.addedClasses.map((c) => (
              <li key={c} className="text-success pl-3" title={c}>+ {localName(c)}</li>
            ))}
          </ul>
        </div>
      )}
      {diff.changedClasses.length > 0 && (
        <div>
          <div className="text-warning font-semibold mb-1">~ {diff.changedClasses.length} changed class{diff.changedClasses.length === 1 ? '' : 'es'}</div>
          <ul className="space-y-2">
            {diff.changedClasses.map((cc) => (
              <li key={cc.classIri} className="pl-3">
                <div className="text-warning font-semibold" title={cc.classIri}>~ {localName(cc.classIri)}</div>
                <div className="pl-3 text-muted-foreground">
                  {cc.addedProperties.map((p) => (
                    <div key={p} className="text-success" title={p}>+ {localName(p)}</div>
                  ))}
                  {cc.removedProperties.map((p) => (
                    <div key={p} className="text-error" title={p}>− {localName(p)}</div>
                  ))}
                  {cc.wrapperChanged && <div className="text-warning">~ wrapper SQL changed</div>}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};

export default SchemaDiffList;
