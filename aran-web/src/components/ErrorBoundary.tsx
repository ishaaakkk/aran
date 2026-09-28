import { Component, type ErrorInfo, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { ErrorReference } from './ErrorNotice';
import { errorReference, isChunkLoadError } from '@/lib/errors';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
  /**
   * A caught error is cleared when this changes. Every route renders the same
   * RequireAuth → AppLayout → ErrorBoundary chain, so React keeps the boundary — and its error —
   * across navigation: one view crashing used to make every other page read "Something went
   * wrong" until a reload (UI audit J8-F6). The route's pathname is the key.
   */
  resetKey?: string;
}

interface State {
  error: Error | null;
}

class ErrorBoundaryInner extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] uncaught render error', error, info.componentStack);
  }

  componentDidUpdate(prev: Props) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    if (this.props.fallback) return this.props.fallback;

    if (isChunkLoadError(error)) {
      return (
        <div role="alert" className="flex flex-col items-center justify-center h-full p-8 text-center gap-4">
          <p className="text-sm font-semibold text-foreground">Aran was updated</p>
          <p className="text-xs text-muted-foreground max-w-md">
            This page belongs to the version that was running when you opened the tab. Reload to
            get the new one.
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="h-8 px-4 rounded-md bg-primary text-primary-foreground text-[12px] font-semibold hover:bg-primary/90 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
          >
            Reload
          </button>
        </div>
      );
    }

    const reference = errorReference(error);
    return (
      <div role="alert" className="flex flex-col items-center justify-center h-full p-8 text-center gap-4">
        <p className="text-sm font-semibold text-error">This page hit an error it could not recover from.</p>
        <p className="text-xs text-muted-foreground font-mono break-all max-w-lg select-text">{error.message}</p>
        {reference && <ErrorReference reference={reference} />}
        <p className="text-xs text-muted-foreground max-w-md">
          Other pages are unaffected — use the navigation to leave this one.
        </p>
        <button
          type="button"
          className="h-8 px-4 rounded-md border border-border text-[12px] font-semibold hover:border-primary hover:text-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
          onClick={() => this.setState({ error: null })}
        >
          Try again
        </button>
      </div>
    );
  }
}

/** The boundary every route renders, cleared whenever the route changes (see `resetKey`). */
export function ErrorBoundary({ children, fallback }: { children: ReactNode; fallback?: ReactNode }) {
  const { pathname } = useLocation();
  return <ErrorBoundaryInner resetKey={pathname} fallback={fallback}>{children}</ErrorBoundaryInner>;
}

export { ErrorBoundaryInner };
