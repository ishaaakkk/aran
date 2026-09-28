import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { RequireAuth } from '@/components/RequireAuth';
import { AppLayout } from '@/layouts/AppLayout';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { LoginView } from '@/views/LoginView';

/**
 * Routed views are code-split: each one becomes its own chunk, fetched the first
 * time its route is visited. Loading all eleven eagerly meant every user paid for
 * the query workbench and the dataset browser — the two heaviest views in the
 * app — before they could see the dashboard.
 *
 * Deliberately NOT split: `RequireAuth`, `AppLayout`/`IconRail` (they wrap every
 * route, so splitting them just adds a round trip to every first paint) and
 * `LoginView` (the landing route for an unauthenticated user — splitting it puts
 * a spinner in front of the very first screen anyone sees).
 *
 * `.then(m => ({ default: m.X }))` because these views export named symbols; some
 * have no default export at all.
 */
const OfflineView = lazy(() => import('@/views/OfflineView').then((m) => ({ default: m.OfflineView })));
const TenantSelectView = lazy(() => import('@/views/TenantSelectView').then((m) => ({ default: m.TenantSelectView })));
const DashboardView = lazy(() => import('@/views/DashboardView').then((m) => ({ default: m.DashboardView })));
const ProjectListView = lazy(() => import('@/views/ProjectListView').then((m) => ({ default: m.ProjectListView })));
const RepositoriesListView = lazy(() => import('@/views/RepositoriesListView').then((m) => ({ default: m.RepositoriesListView })));
const DatasetsListView = lazy(() => import('@/views/DatasetsListView').then((m) => ({ default: m.DatasetsListView })));
const OqlPlanView = lazy(() => import('@/views/OqlPlanView').then((m) => ({ default: m.OqlPlanView })));
const AlignmentsView = lazy(() => import('@/views/AlignmentsView').then((m) => ({ default: m.AlignmentsView })));
const VersionsView = lazy(() => import('@/views/VersionsView').then((m) => ({ default: m.VersionsView })));
const ProductsView = lazy(() => import('@/views/ProductsView').then((m) => ({ default: m.ProductsView })));
// Default export, unlike the views above — and split for a second reason: most
// people never open administration at all.
const AdminView = lazy(() => import('@/views/AdminView'));

/** Shown only while a route chunk is in flight — matches the app's other loading states. */
const RouteFallback = () => (
  <div className="flex-grow flex flex-col items-center justify-center gap-3 min-h-0 py-16 text-muted-foreground select-none">
    <Loader2 className="animate-spin w-8 h-8 text-primary/50" />
    <span className="font-mono text-xs uppercase tracking-widest">Loading...</span>
  </div>
);

function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<RouteFallback />}>
        <Routes>
          {/* Public routes */}
          <Route path="/login" element={<LoginView />} />
          <Route path="/offline" element={<OfflineView />} />

          {/* Protected routes — each wrapped in RequireAuth + AppLayout + ErrorBoundary */}
          <Route path="/tenants" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><TenantSelectView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/dashboard" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><DashboardView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/projects" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><ProjectListView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/repositories" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><RepositoriesListView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/datasets" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><DatasetsListView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          {/* The dataset detail is a routed page, not a modal: linkable, refreshable,
              two-tab friendly (redesign brief, datasets HIGH #2). Same component — the
              detail is URL-driven state inside it. */}
          <Route path="/datasets/:datasetId" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><DatasetsListView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/alignments" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><AlignmentsView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/oql" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><OqlPlanView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/versions" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><VersionsView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          <Route path="/products" element={
            <RequireAuth>
              <AppLayout><ErrorBoundary><ProductsView /></ErrorBoundary></AppLayout>
            </RequireAuth>
          } />
          {/* Administration — deliberately NOT wrapped in AppLayout. It is a separate
              surface with its own chrome (AdminLayout), not a page in the workspace;
              see the comment on AdminLayout for why.

              Not gated in the router either: the workspace hides the entry for members,
              but /api/tenant/admin/** is OWNER-only server-side and that is the real
              boundary. A route guard here would only decide what a hand-typed URL
              renders. */}
          {/* Users, not Usage: the admin area is opened to do something far more often than to read a number. */}
          <Route path="/admin" element={<Navigate to="/admin/users" replace />} />
          <Route path="/admin/:section" element={
            <RequireAuth>
              <ErrorBoundary><AdminView /></ErrorBoundary>
            </RequireAuth>
          } />


          {/* Catch-all redirect */}
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}

export default App;
