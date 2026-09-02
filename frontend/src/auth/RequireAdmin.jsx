import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './authContext.js'
import AccessDeniedPage from '../pages/admin/AccessDeniedPage.jsx'

/**
 * Route guard for the admin area, layered on the existing auth
 * truth — no second mechanism. Anonymous visitors are sent to
 * /login with the attempted path preserved (same contract as
 * RequireAuth). An authenticated USER gets a real 403-style
 * "access denied" state instead of the admin interface; only
 * ADMIN passes through.
 */
export default function RequireAdmin({ children }) {
  const { isAuthenticated, user } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  if (user?.role !== 'ADMIN') {
    return <AccessDeniedPage />
  }

  return children
}