import React, { useCallback, useRef, useState } from 'react';
import { ConfirmDialog } from './ConfirmDialog';
import type { ConfirmRequest } from '@/lib/confirm';

/**
 * A promise-returning confirm for handlers that used `window.confirm`: `if (!(await confirm({…})))
 * return;` — render the returned element once in the component.
 */
export function useConfirm(): [(request: ConfirmRequest) => Promise<boolean>, React.ReactNode] {
  const [request, setRequest] = useState<ConfirmRequest | null>(null);
  const resolver = useRef<((ok: boolean) => void) | null>(null);

  const confirm = useCallback((next: ConfirmRequest) => {
    resolver.current?.(false);
    setRequest(next);
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  const settle = (ok: boolean) => {
    resolver.current?.(ok);
    resolver.current = null;
    setRequest(null);
  };

  const element = request ? (
    <ConfirmDialog open {...request} onConfirm={() => settle(true)} onCancel={() => settle(false)} />
  ) : null;
  return [confirm, element];
}

