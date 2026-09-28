import type { Me } from '@/services/types';

/**
 * How the UI reads the optional surfaces THIS deployment turned on.
 *
 * One bundle is served by every deployment, so no build-time env can decide what exists here:
 * the server publishes it on `GET /api/me` as `features`, and the SPA renders off that.
 *
 * A plain function, not a component — kept out of the views for the same reason
 * `productAccessMode.ts` is (react-refresh/only-export-components).
 */

/**
 * Whether the shape-as-cube surface exists on this server (`aran.cubes.enabled`, off by
 * default).
 *
 * Anything other than an explicit `true` is off, and that is the safe direction rather than a
 * defensive habit: with the flag off `CubeController` is not even a bean, so every cube route
 * answers 404 — a button that can only produce 404s is worse than no button. `me` is null
 * before `/api/me` has answered, and `features` is absent from a server that predates the flag.
 */
export function cubesEnabledFrom(me: Me | null): boolean {
  return me?.features?.cubes === true;
}
