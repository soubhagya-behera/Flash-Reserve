/* ============================================================
   FlashReserve — Auth context + hook
   The context object lives in its own module so the provider
   component and the useAuth hook can import it without cycles.
   ============================================================ */

import { createContext, useContext } from 'react'

export const AuthContext = createContext(null)

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside an <AuthProvider>.')
  }
  return context
}
