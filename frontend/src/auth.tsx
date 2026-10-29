import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, type Me } from './api'

interface AuthState {
  me: Me | null
  loading: boolean
  login: (username: string, password: string) => Promise<Me>
  /** POSTs to a public sign-in endpoint (register, google) and keeps the returned user. */
  signInWith: (path: string, body: object) => Promise<Me>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState>(null!)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  // On page load/refresh, ask the server whether the session cookie is still valid.
  const refresh = useCallback(async () => {
    try {
      const { data } = await api.get('/auth/session')
      setMe(data.authenticated ? data.user : null)
    } catch {
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    // A 401 anywhere (expired/revoked session) drops the user back to the login screen
    const id = api.interceptors.response.use(undefined, (err) => {
      if (err.response?.status === 401 && !String(err.config?.url).includes('/auth/')) setMe(null)
      return Promise.reject(err)
    })
    return () => api.interceptors.response.eject(id)
  }, [refresh])

  const signInWith = async (path: string, body: object) => {
    await api.get('/auth/csrf')
    const { data } = await api.post<Me>(path, body)
    setMe(data)
    return data
  }

  const login = (username: string, password: string) => signInWith('/auth/login', { username, password })

  const logout = async () => {
    try { await api.post('/auth/logout') } finally { setMe(null) }
  }

  return <AuthContext.Provider value={{ me, loading, login, signInWith, logout, refresh }}>{children}</AuthContext.Provider>
}

export const useAuth = () => useContext(AuthContext)

export const homeFor = (role?: string) =>
  role === 'ADMIN' ? '/admin' : role === 'EMPLOYEE' ? '/staff' : '/customer'
