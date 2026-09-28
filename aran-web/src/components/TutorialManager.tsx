import React, { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
// Types only — erased at build time, so this does not pull driver.js into the
// bundle. The implementation and its stylesheet are imported on demand below.
import type { DriveStep, Driver } from 'driver.js';
import { useAuthStore } from '../stores/authStore';
import { useTutorialStore } from '../stores/useTutorialStore';
import { tourForPath, tourSteps } from './tours/tourRegistry';
import { isDemoUser } from '../services/demo/demoInterceptor';

/**
 * Drops any step whose anchor is not in the DOM right now.
 *
 * Most anchors sit behind data-dependent branches — an empty tenant renders no
 * versions table and no products list — and driver.js turns an unresolvable
 * selector into an unanchored centre-screen popover rather than an error. Without
 * this filter a tour on a fresh tenant reads as a stack of context-free blurbs.
 * `tourRegistry.test.ts` is what catches an anchor that is missing by mistake;
 * this handles the ones that are legitimately absent.
 */
function resolvableSteps(steps: DriveStep[]): DriveStep[] {
  return steps.filter((step) => {
    if (typeof step.element !== 'string') return true; // deliberate body-level popover
    if (document.querySelector(step.element)) return true;
    if (import.meta.env.DEV) {
      console.warn(`[tour] no element matches ${step.element}; dropping the step`);
    }
    return false;
  });
}

export const TutorialManager: React.FC = () => {
  const location = useLocation();
  const me = useAuthStore((s) => s.me);

  // Selected individually: `seenTours` is the only slice whose changes should
  // re-run the effect, and subscribing to the whole store would also re-run it
  // on every unrelated action identity.
  const seenTours = useTutorialStore((s) => s.seenTours);
  const manualTrigger = useTutorialStore((s) => s.manualTrigger);
  const markTourSeen = useTutorialStore((s) => s.markTourSeen);
  const clearManualTrigger = useTutorialStore((s) => s.clearManualTrigger);

  const driverObj = useRef<Driver | null>(null);

  useEffect(() => {
    const tour = tourForPath(location.pathname);
    if (!tour) return;

    // Auto-start is demo-only; a manual replay works for any user. "Demo" is the one gate api.ts
    // uses for the mocked backend (the VITE_DEMO build opt-in AND the demo identity), not a
    // second definition read off `me` — the two could disagree about the same session.
    const isDemo = me !== null && isDemoUser();
    const shouldStart = (isDemo && !seenTours[tour.id]) || manualTrigger === tour.id;
    if (!shouldStart) return;

    // driver.js is fetched on demand, so teardown can happen between the timer
    // firing and the module landing; without this the tour would start on a
    // route the user has already left.
    let cancelled = false;

    const startTour = async () => {
      // Outside the demo, a scenario tour opens by saying it narrates the demo scenario and what
      // an empty workspace can do here (J1-F12).
      const steps = resolvableSteps(tourSteps(tour, { demo: isDemo }));

      // Nothing left to show — settle the state anyway, or the effect re-runs
      // on the next render and the user is stuck in a silent retry loop.
      if (steps.length === 0) {
        markTourSeen(tour.id);
        clearManualTrigger();
        return;
      }

      // Loaded here rather than at module scope: a tour only ever runs for the
      // demo user or on an explicit replay, so eagerly importing driver.js and
      // its stylesheet shipped a dependency to every user that almost none of
      // them execute.
      const [{ driver }] = await Promise.all([
        import('driver.js'),
        import('driver.js/dist/driver.css'),
      ]);
      if (cancelled) return;

      driverObj.current = driver({
        showProgress: true,
        steps,
        onDestroyStarted: () => {
          driverObj.current?.destroy();
          driverObj.current = null;
          markTourSeen(tour.id);
          clearManualTrigger();
        },
      });
      driverObj.current.drive();
    };

    // The route's own data usually lands a tick or two after navigation.
    const timer = setTimeout(() => {
      void startTour();
    }, 500);

    return () => {
      cancelled = true;
      clearTimeout(timer);
      driverObj.current?.destroy();
      driverObj.current = null;
    };
  }, [location.pathname, me, seenTours, manualTrigger, markTourSeen, clearManualTrigger]);

  return null;
};
