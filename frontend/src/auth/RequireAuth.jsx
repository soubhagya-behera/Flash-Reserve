import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './authContext.js'

/**
 * Route guard for authenticated pages. An anonymous visitor is sent
 * to /login with the attempted path in router state, so the existing
 * login flow returns them exactly where they were heading. No second
 * auth mechanism — this reads the same AuthProvider truth as the rest
 * of the app.
 */
export default function RequireAuth({ children }) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return children
}
