import { useCallback, useMemo, useState } from 'react'
import { AuthContext } from './authContext.js'
import * as authService from '../services/authService.js'
import { clearAuth, loadAuth, saveAuth } from '../services/tokenStorage.js'

/**
 * Single source of authentication truth for the whole app.
 * Restoration is synchronous by design: the backend issues a JWT
 * immediately on register/login and has no session/me endpoint,
 * so there is no async initialization phase to wait for.
 */
export default function AuthProvider({ children }) {
  const [auth, setAuth] = useState(() => loadAuth())

  const login = useCallback(async (credentials) => {
    const result = await authService.login(credentials)
    saveAuth(result)
    setAuth(result)
    return result.user
  }, [])

  const register = useCallback(async (details) => {
    const result = await authService.register(details)
    saveAuth(result)
    setAuth(result)
    return result.user
  }, [])

  const logout = useCallback(() => {
    clearAuth()
    setAuth(null)
  }, [])

  const value = useMemo(
    () => ({
      user: auth?.user ?? null,
      token: auth?.token ?? null,
      isAuthenticated: Boolean(auth?.token),
      login,
      register,
      logout,
    }),
    [auth, login, register, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
