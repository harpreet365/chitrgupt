import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { api, errorMessage, inr, when } from '../api'
import { homeFor, useAuth } from '../auth'
import { Alert, StatusBadge, usePaged } from '../components'

export function LoginPage() {
  const { me, login } = useAuth()
  const nav = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  if (me) return <Navigate to={me.mustChangePassword ? '/security' : homeFor(me.role)} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!username.trim() || !password) { setError('Enter your username and password.'); return }
    setBusy(true); setError(undefined)
    try {
      const u = await login(username.trim(), password)
      nav(u.mustChangePassword ? '/security' : homeFor(u.role), { replace: true })
    } catch (err) { setError(errorMessage(err)) } finally { setBusy(false) }
  }

  return (
    <>
      <div className="banner">Educational banking simulation - no real money, no real payment networks.</div>
      <div className="card login">
        <h1>BMS Bank</h1>
        <p className="muted">Sign in to continue</p>
        <form onSubmit={submit} noValidate>
          <Alert error={error} />
          <label>Username<input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus /></label>
          <label>Password
            <div className="row" style={{ flexWrap: 'nowrap' }}>
              <input style={{ flex: 1 }} type={show ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
              <button type="button" className="secondary" onClick={() => setShow(!show)} aria-label={show ? 'Hide password' : 'Show password'}>{show ? 'Hide' : 'Show'}</button>
            </div>
          </label>
          <button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>
        <SignInOptions onError={setError} />
      </div>
    </>
  )
}

interface Providers { selfRegistration: boolean; googleClientId?: string }

function useProviders() {
  const [p, setP] = useState<Providers>()
  useEffect(() => { api.get<Providers>('/auth/providers').then((r) => setP(r.data)).catch(() => setP({ selfRegistration: false })) }, [])
  return p
}

/** "Create account" link and the Google button, shown under the sign-in form when the server enables them. */
function SignInOptions({ onError }: { onError: (e?: string) => void }) {
  const p = useProviders()
  if (!p || (!p.selfRegistration && !p.googleClientId)) return null
  return (
    <div style={{ marginTop: 16 }}>
      {p.googleClientId && <GoogleButton clientId={p.googleClientId} onError={onError} />}
      {p.selfRegistration && <p className="muted" style={{ marginTop: 12 }}>New customer? <Link to="/register">Create an account</Link></p>}
    </div>
  )
}

interface GoogleId {
  initialize: (o: { client_id: string; callback: (r: { credential: string }) => void }) => void
  renderButton: (el: HTMLElement, o: Record<string, unknown>) => void
}
declare global { interface Window { google?: { accounts: { id: GoogleId } } } }

let gsiScript: Promise<void> | undefined
const loadGsi = () => gsiScript ??= new Promise<void>((resolve, reject) => {
  const s = document.createElement('script')
  s.src = 'https://accounts.google.com/gsi/client'
  s.async = true
  s.onload = () => resolve()
  s.onerror = () => { gsiScript = undefined; reject(new Error('Could not load Google sign-in')) }
  document.head.appendChild(s)
})

/** Google Identity Services button; the ID token it returns is verified by the backend. */
function GoogleButton({ clientId, onError }: { clientId: string; onError: (e?: string) => void }) {
  const { signInWith } = useAuth()
  const nav = useNavigate()
  const el = useRef<HTMLDivElement>(null)
  useEffect(() => {
    let live = true
    loadGsi().then(() => {
      if (!live || !el.current || !window.google) return
      window.google.accounts.id.initialize({
        client_id: clientId,
        callback: async ({ credential }) => {
          onError(undefined)
          try {
            const u = await signInWith('/auth/google', { credential })
            nav(u.mustChangePassword ? '/security' : homeFor(u.role), { replace: true })
          } catch (err) { onError(errorMessage(err)) }
        },
      })
      window.google.accounts.id.renderButton(el.current, { theme: 'outline', size: 'large', text: 'continue_with', width: 300 })
    }).catch((e: Error) => onError(e.message))
    return () => { live = false }
  }, [clientId]) // eslint-disable-line react-hooks/exhaustive-deps
  return <div ref={el} style={{ minHeight: 44 }} />
}

export function RegisterPage() {
  const { me, signInWith } = useAuth()
  const nav = useNavigate()
  const p = useProviders()
  const [f, setF] = useState({ fullName: '', username: '', email: '', phone: '', dateOfBirth: '', password: '', confirm: '', accountType: 'SAVINGS' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  if (me) return <Navigate to={homeFor(me.role)} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (f.password !== f.confirm) { setError('Passwords do not match.'); return }
    setBusy(true); setError(undefined)
    try {
      const { confirm: _, ...rest } = f
      const body = Object.fromEntries(Object.entries(rest).filter(([, v]) => v !== '').map(([k, v]) => [k, k === 'password' ? v : v.trim()]))
      const u = await signInWith('/auth/register', body)
      nav(homeFor(u.role), { replace: true })
    } catch (err) { setError(errorMessage(err)) } finally { setBusy(false) }
  }
  const field = (k: keyof typeof f, label: string, type = 'text', autoComplete?: string) =>
    <label>{label}<input type={type} autoComplete={autoComplete} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} /></label>

  return (
    <>
      <div className="banner">Educational banking simulation - no real money, no real payment networks.</div>
      <div className="card login">
        <h1>Create your account</h1>
        <p className="muted">Opens a customer login and your first bank account.</p>
        {p && !p.selfRegistration ? <Alert error="Online registration is not available. Please visit a branch." /> : (
          <form onSubmit={submit} noValidate>
            <Alert error={error} />
            {field('fullName', 'Full name *', 'text', 'name')}
            {field('username', 'Username * (3-50: a-z, 0-9, . _ -)', 'text', 'username')}
            {field('email', 'Email *', 'email', 'email')}
            {field('dateOfBirth', 'Date of birth * (18+)', 'date', 'bday')}
            {field('phone', 'Phone', 'tel', 'tel')}
            {field('password', 'Password * (10+ chars, upper, lower, digit)', 'password', 'new-password')}
            {field('confirm', 'Confirm password *', 'password', 'new-password')}
            <label>Account type<select value={f.accountType} onChange={(e) => setF({ ...f, accountType: e.target.value })}>
              <option value="SAVINGS">Savings</option><option value="CURRENT">Current</option></select></label>
            <button type="submit" disabled={busy}>{busy ? 'Creating account…' : 'Create account'}</button>
          </form>
        )}
        {p?.googleClientId && <div style={{ marginTop: 16 }}><p className="muted">Or</p><GoogleButton clientId={p.googleClientId} onError={setError} /></div>}
        <p className="muted" style={{ marginTop: 12 }}>Already have an account? <Link to="/login">Sign in</Link></p>
      </div>
    </>
  )
}

export function SecurityPage() {
  const { me, refresh } = useAuth()
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [msg, setMsg] = useState<{ ok?: string; error?: string }>({})
  const logins = usePaged<{ id: number; outcome: string; ipAddress: string; createdAt: string }>('/me/login-activity', { size: 10 })

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (form.newPassword !== form.confirm) { setMsg({ error: 'New passwords do not match.' }); return }
    try {
      await api.post('/auth/password', { currentPassword: form.currentPassword, newPassword: form.newPassword })
      setForm({ currentPassword: '', newPassword: '', confirm: '' })
      setMsg({ ok: 'Password changed. Other sessions were signed out.' })
      await refresh()
    } catch (err) { setMsg({ error: errorMessage(err) }) }
  }

  return (
    <>
      <h1>Security</h1>
      {me?.mustChangePassword && <div className="alert error">You must set a new password before continuing.</div>}
      <div className="card">
        <h2>Change password</h2>
        <Alert {...msg} />
        <form className="inline" onSubmit={submit}>
          <label>Current password<input type="password" autoComplete="current-password" value={form.currentPassword} onChange={(e) => setForm({ ...form, currentPassword: e.target.value })} /></label>
          <label>New password<input type="password" autoComplete="new-password" value={form.newPassword} onChange={(e) => setForm({ ...form, newPassword: e.target.value })} /></label>
          <label>Confirm<input type="password" autoComplete="new-password" value={form.confirm} onChange={(e) => setForm({ ...form, confirm: e.target.value })} /></label>
          <button type="submit">Update</button>
        </form>
        <p className="muted">At least 10 characters with upper-case, lower-case and a digit.</p>
      </div>
      {!me?.mustChangePassword && (
        <div className="card">
          <h2>Recent sign-in activity</h2>
          <p className="muted">Previous successful sign-in: {when(me?.previousLoginAt)} · Last failed attempt: {when(me?.lastFailedLoginAt)}</p>
          <table>
            <thead><tr><th>Time</th><th>Result</th><th>IP</th></tr></thead>
            <tbody>{logins.data?.content.map((l) => <tr key={l.id}><td>{when(l.createdAt)}</td><td><StatusBadge s={l.outcome} /></td><td>{l.ipAddress}</td></tr>)}</tbody>
          </table>
        </div>
      )}
    </>
  )
}

interface Detail {
  transaction: { reference: string; type: string; status: string; amount: number; sourceAccountNumber?: string; destinationAccountNumber?: string;
    channel: string; description?: string; failureReason?: string; initiatedBy: string; createdAt: string; direction?: string }
  ledgerEntries: { id: number; accountNumber: string; entryType: string; amount: number; balanceAfter: number }[]
  flags: { id: number; source: string; reason: string; status: string; flaggedBy: string }[]
  receiptNotice: string
}

/** Receipt / detail view. The server returns 404 for transactions the caller may not see. */
export function TransactionDetailPage() {
  const { reference } = useParams()
  const { me } = useAuth()
  const [d, setD] = useState<Detail>()
  const [error, setError] = useState<string>()
  const [reason, setReason] = useState('')
  const [msg, setMsg] = useState<{ ok?: string; error?: string }>({})
  const load = () => api.get(`/transactions/${reference}`).then((r) => setD(r.data)).catch((e) => setError(errorMessage(e)))
  useEffect(() => { load() }, [reference]) // eslint-disable-line react-hooks/exhaustive-deps

  const flag = async (e: FormEvent) => {
    e.preventDefault()
    try { await api.post(`/transactions/${reference}/flags`, { reason }); setReason(''); setMsg({ ok: 'Flagged for review.' }); load() }
    catch (err) { setMsg({ error: errorMessage(err) }) }
  }

  if (error) return <Alert error={error} />
  if (!d) return <p className="muted">Loading…</p>
  const t = d.transaction
  return (
    <>
      <h1>Transaction receipt</h1>
      <div className="card">
        <table><tbody>
          <tr><th>Reference</th><td className="mono">{t.reference}</td></tr>
          <tr><th>Type</th><td>{t.type}</td></tr>
          <tr><th>Status</th><td><StatusBadge s={t.status} /> {t.failureReason}</td></tr>
          <tr><th>Amount</th><td>{inr(t.amount)}</td></tr>
          <tr><th>From</th><td className="mono">{t.sourceAccountNumber ?? 'Cash (simulated)'}</td></tr>
          <tr><th>To</th><td className="mono">{t.destinationAccountNumber ?? 'Cash (simulated)'}</td></tr>
          <tr><th>Description</th><td>{t.description ?? '-'}</td></tr>
          <tr><th>Channel / initiated by</th><td>{t.channel} · {t.initiatedBy}</td></tr>
          <tr><th>Date</th><td>{when(t.createdAt)}</td></tr>
        </tbody></table>
        <p className="muted">{d.receiptNotice}</p>
      </div>
      {d.ledgerEntries.length > 0 && (
        <div className="card"><h2>Ledger entries</h2>
          <table><thead><tr><th>Account</th><th>Entry</th><th className="num">Amount</th><th className="num">Balance after</th></tr></thead>
            <tbody>{d.ledgerEntries.map((l) => <tr key={l.id}><td className="mono">{l.accountNumber}</td><td>{l.entryType}</td><td className="num">{inr(l.amount)}</td><td className="num">{inr(l.balanceAfter)}</td></tr>)}</tbody></table>
        </div>
      )}
      {me?.role !== 'CUSTOMER' && (
        <div className="card"><h2>Review flags</h2>
          {d.flags.length === 0 && <p className="muted">No flags.</p>}
          {d.flags.map((f) => <p key={f.id}><StatusBadge s={f.status} /> {f.source} by {f.flaggedBy}: {f.reason}</p>)}
          <Alert {...msg} />
          <form className="inline" onSubmit={flag}>
            <label>Reason<input value={reason} onChange={(e) => setReason(e.target.value)} style={{ width: 320 }} /></label>
            <button type="submit" disabled={reason.trim().length < 5}>Flag as suspicious</button>
          </form>
        </div>
      )}
    </>
  )
}
