import { create } from 'zustand'
import { api } from '@/services/api'
import { logout as kcLogout } from '@/services/keycloak'
import type { Me, TenantMembership } from '@/services/types'

interface AuthState {
  me: Me | null
  loading: boolean
  isReady: boolean
  /**
   * Where loading `/api/me` stands. `error` is rendered in place by RequireAuth, with the
   * failure in `sessionError` — it used to become a redirect to /offline, whose green health
   * probes redirected straight back: a reload loop several times a second (UI audit J1-F1).
   */
  sessionStatus: 'idle' | 'loading' | 'ready' | 'error'
  sessionError: unknown
  /** The token could not be refreshed: the realm's idle or absolute limit passed (J7-F6). */
  sessionExpired: boolean
  setSessionExpired: (expired: boolean) => void
  tenants: TenantMembership[]
  activeTenantId: string | null
  activeTenant: TenantMembership | null
  /** Resolves true once the session is loaded; never throws — a failure lands in `sessionError`. */
  loadMe: () => Promise<boolean>
  updateMe: (updated: { displayName?: string; email?: string }) => void
  logout: () => void
}

const LS_USER_OVERRIDE_PREFIX = 'aran.user.profile.override.'

function getOverride(subject: string): Partial<Me> {
  try {
    const data = localStorage.getItem(LS_USER_OVERRIDE_PREFIX + subject)
    return data ? JSON.parse(data) : {}
  } catch {
    return {}
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  me: null,
  loading: false,
  isReady: false,
  sessionStatus: 'idle',
  sessionError: null,
  sessionExpired: false,
  tenants: [],
  activeTenantId: null,
  activeTenant: null,

  setSessionExpired: (expired) => set({ sessionExpired: expired }),

  loadMe: async () => {
    set({ loading: true, sessionStatus: 'loading', sessionError: null })
    try {
      const serverMe = await api.me()
      const override = getOverride(serverMe.subject)
      const mergedMe = { ...serverMe, ...override }
      
      // The active tenant is the only one: a user holds exactly one membership
      // (V23, uq_membership_user), so there is nothing to choose between.
      //
      // This used to read a `tenant_id` claim off the access token. That claim was
      // removed from the realm with M15 — a user's tenant lived in Keycloak and in
      // Aran's membership table with nothing keeping the two in agreement, so the
      // membership row won. Left as it was, `activeTenant` would now be null on
      // every login and the rail would show no organisation at all.
      const tenants = mergedMe.tenants ?? []
      const activeTenant = tenants[0] ?? null

      set({
        me: mergedMe,
        tenants,
        activeTenantId: activeTenant?.tenantId ?? null,
        activeTenant,
        isReady: true,
        sessionStatus: 'ready',
      })
      return true
    } catch (err: unknown) {
      set({ isReady: false, sessionStatus: 'error', sessionError: err })
      return false
    } finally {
      set({ loading: false })
    }
  },

  updateMe: (updated) => {
    const { me } = get()
    if (!me) return

    const updatedMe = { ...me, ...updated }
    try {
      localStorage.setItem(LS_USER_OVERRIDE_PREFIX + me.subject, JSON.stringify(updated))
    } catch (e) {
      console.error('Failed to persist user profile overrides:', e)
    }

    const tenants = updatedMe.tenants ?? []
    const activeTenant = tenants.find((t) => t.tenantId === get().activeTenantId) ?? null

    set({ me: updatedMe, tenants, activeTenant })
  },

  logout: () => {
    const { me } = get()
    const subject = me?.subject
    set({
      me: null, tenants: [], activeTenantId: null, activeTenant: null, isReady: false,
      sessionStatus: 'idle', sessionError: null, sessionExpired: false,
    })
    if (subject) {
      try {
        localStorage.removeItem(LS_USER_OVERRIDE_PREFIX + subject)
      } catch {
        // We are logging out regardless; a failed cleanup must not block that.
      }
    }
    kcLogout()
  },
}))
export default useAuthStore;
