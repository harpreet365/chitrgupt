import { useEffect, useState, type FormEvent } from 'react'
import { api, errorMessage, inr, when, type Account } from '../api'
import { Alert, Pager, Stat, StatusBadge, usePaged } from '../components'

const PERMISSIONS = ['CUSTOMER_VIEW', 'CUSTOMER_REGISTER', 'ACCOUNT_CREATE', 'CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'TRANSFER', 'TRANSACTION_FLAG']

export function AdminOverview() {
  const [s, setS] = useState<Record<string, number | string>>({})
  const [trend, setTrend] = useState<{ series: { date: string; volume: string; count: number }[]; changePercent: number | null }>()
  const [trendError, setTrendError] = useState<string>()
  const [recon, setRecon] = useState<{ consistent: boolean; accountsChecked: number; transactionsChecked: number }>()
  useEffect(() => {
    api.get('/admin/stats').then((r) => setS(r.data)).catch(() => {})
    api.get('/admin/analytics/trends?days=30').then((r) => setTrend(r.data)).catch((e) => setTrendError(errorMessage(e)))
  }, [])
  const max = Math.max(1, ...(trend?.series.map((p) => Number(p.volume)) ?? [1]))
  return (
    <>
      <h1>System overview</h1>
      <div className="grid">
        <Stat label="Customers (active)" value={`${s.totalCustomers ?? '-'} (${s.activeCustomers ?? '-'})`} />
        <Stat label="Active accounts" value={s.activeAccounts ?? '-'} />
        <Stat label="Deposits held" value={inr(s.totalDepositsHeld)} />
        <Stat label="Transactions (failed)" value={`${s.transactionCount ?? '-'} (${s.failedTransactionCount ?? '-'})`} />
        <Stat label="Completed volume" value={inr(s.transactionVolume)} />
        <Stat label="Today" value={`${s.transactionsToday ?? '-'} · ${inr(s.volumeToday)}`} />
        <Stat label="Open flags" value={s.openFlags ?? '-'} />
        <Stat label="Failed logins, 24h" value={s.failedLogins24h ?? '-'} />
      </div>
      <div className="card">
        <h2>Daily volume, last 30 days <span className="badge">Python analytics</span></h2>
        <Alert error={trendError} />
        {trend && <>
          <div className="bars" role="img" aria-label="Bar chart of daily completed transaction volume">
            {trend.series.map((p) => <div key={p.date} title={`${p.date}: ${inr(p.volume)} (${p.count})`} style={{ height: `${(Number(p.volume) / max) * 100}%` }} />)}
          </div>
          <p className="muted">{trend.series[0]?.date} – {trend.series.at(-1)?.date} · change, 2nd vs 1st half: {trend.changePercent ?? 'n/a'}%</p>
        </>}
      </div>
      <div className="card">
        <h2>Ledger reconciliation</h2>
        <button className="secondary" onClick={() => api.get('/admin/reconciliation').then((r) => setRecon(r.data))}>Run check</button>
        {recon && <p>{recon.consistent ? <span className="badge ok">CONSISTENT</span> : <span className="badge bad">MISMATCH</span>} {recon.accountsChecked} accounts, {recon.transactionsChecked} transactions checked.</p>}
      </div>
    </>
  )
}

interface UserRow { id: number; username: string; email: string; fullName: string; role: string; status: string; permissions: string[]; lockedUntil?: string; lastLoginAt?: string; demo: boolean }

export function UsersPage() {
  const [filter, setFilter] = useState({ q: '', role: '', status: '' })
  const [applied, setApplied] = useState(filter)
  const [page, setPage] = useState(0)
  const { data, error, reload } = usePaged<UserRow>('/admin/users', { ...applied, page, size: 15 })
  const [msg, setMsg] = useState<{ ok?: string; error?: string }>({})
  const [edit, setEdit] = useState<UserRow>()
  const [nu, setNu] = useState({ username: '', email: '', fullName: '', role: 'EMPLOYEE' })

  const act = async (fn: () => Promise<{ data: { temporaryPassword?: string } }>, ok: string) => {
    try { const r = await fn(); setMsg({ ok: r.data.temporaryPassword ? `${ok} Temporary password (shown once): ${r.data.temporaryPassword}` : ok }); reload() }
    catch (err) { setMsg({ error: errorMessage(err) }) }
  }
  const create = (e: FormEvent) => { e.preventDefault(); act(() => api.post('/admin/users', nu), 'User created.') }

  return (
    <>
      <h1>Users and roles</h1>
      <Alert {...msg} />
      <div className="card">
        <h2>Create staff user</h2>
        <form className="inline" onSubmit={create}>
          <label>Full name<input value={nu.fullName} onChange={(e) => setNu({ ...nu, fullName: e.target.value })} /></label>
          <label>Username<input value={nu.username} onChange={(e) => setNu({ ...nu, username: e.target.value })} /></label>
          <label>Email<input type="email" value={nu.email} onChange={(e) => setNu({ ...nu, email: e.target.value })} /></label>
          <label>Role<select value={nu.role} onChange={(e) => setNu({ ...nu, role: e.target.value })}><option>EMPLOYEE</option><option>ADMIN</option></select></label>
          <button type="submit">Create</button>
        </form>
        <p className="muted">Customers are registered through the employee onboarding screen.</p>
      </div>
      <div className="card">
        <form className="inline" style={{ marginBottom: 12 }} onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(filter) }}>
          <label>Search<input value={filter.q} onChange={(e) => setFilter({ ...filter, q: e.target.value })} /></label>
          <label>Role<select value={filter.role} onChange={(e) => setFilter({ ...filter, role: e.target.value })}><option value="">All</option><option>ADMIN</option><option>EMPLOYEE</option><option>CUSTOMER</option></select></label>
          <label>Status<select value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}><option value="">All</option><option>ACTIVE</option><option>SUSPENDED</option><option>DEACTIVATED</option></select></label>
          <button type="submit">Filter</button>
        </form>
        <Alert error={error} />
        <table>
          <thead><tr><th>User</th><th>Role</th><th>Status</th><th>Last login</th><th>Actions</th></tr></thead>
          <tbody>{data?.content.map((u) => (
            <tr key={u.id}>
              <td>{u.fullName}{u.demo && <span className="badge">demo</span>}<div className="muted">{u.username} · {u.email}</div></td>
              <td>{u.role}</td>
              <td><StatusBadge s={u.status} />{u.lockedUntil && new Date(u.lockedUntil) > new Date() && <span className="badge bad">locked</span>}</td>
              <td>{when(u.lastLoginAt)}</td>
              <td><div className="row">
                {u.status === 'ACTIVE'
                  ? <button className="danger" onClick={() => act(() => api.patch(`/admin/users/${u.id}/status`, { status: 'SUSPENDED', reason: 'admin action' }), 'User suspended.')}>Suspend</button>
                  : <button className="secondary" onClick={() => act(() => api.patch(`/admin/users/${u.id}/status`, { status: 'ACTIVE' }), 'User activated.')}>Activate</button>}
                {u.status !== 'DEACTIVATED' && <button className="danger" onClick={() => window.confirm('Deactivate this user?') && act(() => api.patch(`/admin/users/${u.id}/status`, { status: 'DEACTIVATED', reason: 'admin action' }), 'User deactivated.')}>Deactivate</button>}
                {u.role !== 'CUSTOMER' && <button className="secondary" onClick={() => act(() => api.patch(`/admin/users/${u.id}/role`, { role: u.role === 'ADMIN' ? 'EMPLOYEE' : 'ADMIN' }), 'Role changed.')}>Make {u.role === 'ADMIN' ? 'employee' : 'admin'}</button>}
                {u.role === 'EMPLOYEE' && <button className="secondary" onClick={() => setEdit(u)}>Permissions</button>}
                <button className="secondary" onClick={() => act(() => api.post(`/admin/users/${u.id}/reset-password`), 'Password reset.')}>Reset password</button>
                {u.lockedUntil && <button className="secondary" onClick={() => act(() => api.post(`/admin/users/${u.id}/unlock`), 'Unlocked.')}>Unlock</button>}
              </div></td>
            </tr>))}</tbody>
        </table>
        <Pager page={data} onPage={setPage} />
      </div>
      {edit && <PermissionEditor user={edit} onClose={() => { setEdit(undefined); reload() }} />}
    </>
  )
}

function PermissionEditor({ user, onClose }: { user: UserRow; onClose: () => void }) {
  const [perms, setPerms] = useState<string[]>(user.permissions)
  const [error, setError] = useState<string>()
  const save = async () => {
    try { await api.put(`/admin/users/${user.id}/permissions`, { permissions: perms }); onClose() } catch (e) { setError(errorMessage(e)) }
  }
  return (
    <div className="card">
      <h2>Permissions for {user.fullName}</h2>
      <Alert error={error} />
      <div className="row">{PERMISSIONS.map((p) => (
        <label key={p} style={{ flexDirection: 'row', alignItems: 'center' }}>
          <input type="checkbox" checked={perms.includes(p)} onChange={(e) => setPerms(e.target.checked ? [...perms, p] : perms.filter((x) => x !== p))} />{p}
        </label>))}</div>
      <div className="row" style={{ marginTop: 12 }}><button onClick={save}>Save</button><button className="secondary" onClick={onClose}>Cancel</button></div>
    </div>
  )
}

export function AccountsPage() {
  const [q, setQ] = useState('')
  const [applied, setApplied] = useState({ q: '', status: '' })
  const [page, setPage] = useState(0)
  const { data, error, reload } = usePaged<Account>('/accounts', { ...applied, page, size: 15 })
  const [msg, setMsg] = useState<{ ok?: string; error?: string }>({})
  const setStatus = async (a: Account, status: string) => {
    try { await api.patch(`/admin/accounts/${a.id}/status`, { status, reason: 'admin action' }); setMsg({ ok: `${a.accountNumber} is now ${status}` }); reload() }
    catch (e) { setMsg({ error: errorMessage(e) }) }
  }
  return (
    <>
      <h1>Accounts</h1>
      <div className="card">
        <form className="inline" style={{ marginBottom: 12 }} onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied({ ...applied, q }) }}>
          <label>Account no., customer no. or name<input value={q} onChange={(e) => setQ(e.target.value)} /></label>
          <label>Status<select value={applied.status} onChange={(e) => setApplied({ ...applied, status: e.target.value })}><option value="">All</option><option>ACTIVE</option><option>SUSPENDED</option><option>CLOSED</option></select></label>
          <button type="submit">Search</button>
        </form>
        <Alert error={error ?? msg.error} ok={msg.ok} />
        <table>
          <thead><tr><th>Account</th><th>Holder</th><th>Type</th><th>Status</th><th className="num">Balance</th><th /></tr></thead>
          <tbody>{data?.content.map((a) => (
            <tr key={a.id}><td className="mono">{a.accountNumber}</td><td>{a.holderName}<div className="muted">{a.customerNumber}</div></td><td>{a.accountType}</td>
              <td><StatusBadge s={a.status} /></td><td className="num">{inr(a.balance)}</td>
              <td>{a.status === 'ACTIVE' && <button className="danger" onClick={() => setStatus(a, 'SUSPENDED')}>Suspend</button>}
                {a.status === 'SUSPENDED' && <button className="secondary" onClick={() => setStatus(a, 'ACTIVE')}>Activate</button>}
                {a.status !== 'CLOSED' && Number(a.balance) === 0 && <button className="secondary" onClick={() => setStatus(a, 'CLOSED')}>Close</button>}</td></tr>))}</tbody>
        </table>
        <Pager page={data} onPage={setPage} />
        <p className="muted">Balances cannot be edited; they change only through recorded transactions.</p>
      </div>
    </>
  )
}

export function AuditPage() {
  const [f, setF] = useState({ actor: '', action: '', from: '', to: '' })
  const [applied, setApplied] = useState(f)
  const [page, setPage] = useState(0)
  const { data, error } = usePaged<{ id: number; actorUsername: string; actorRole?: string; action: string; targetType?: string; targetId?: string; details?: string; ipAddress?: string; createdAt: string }>('/admin/audit-logs', { ...applied, page, size: 25 })
  return (
    <>
      <h1>Audit log</h1>
      <div className="card">
        <form className="inline" style={{ marginBottom: 12 }} onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(f) }}>
          <label>Actor<input value={f.actor} onChange={(e) => setF({ ...f, actor: e.target.value })} /></label>
          <label>Action<input value={f.action} onChange={(e) => setF({ ...f, action: e.target.value })} placeholder="e.g. TRANSFER" /></label>
          <label>From<input type="date" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} /></label>
          <label>To<input type="date" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} /></label>
          <button type="submit">Filter</button>
        </form>
        <Alert error={error} />
        <table><thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Details</th></tr></thead>
          <tbody>{data?.content.map((a) => <tr key={a.id}><td>{when(a.createdAt)}</td><td>{a.actorUsername}<div className="muted">{a.actorRole}</div></td><td>{a.action}</td><td className="mono">{a.targetType}:{a.targetId}</td><td className="mono" style={{ maxWidth: 360, wordBreak: 'break-all' }}>{a.details}</td></tr>)}</tbody></table>
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}

export function LoginActivityPage() {
  const [f, setF] = useState({ username: '', failedOnly: '' })
  const [applied, setApplied] = useState(f)
  const [page, setPage] = useState(0)
  const { data, error } = usePaged<{ id: number; username: string; outcome: string; ipAddress?: string; userAgent?: string; createdAt: string }>('/admin/login-activity', { ...applied, page, size: 25 })
  return (
    <>
      <h1>Login activity</h1>
      <div className="card">
        <form className="inline" style={{ marginBottom: 12 }} onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(f) }}>
          <label>Username<input value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} /></label>
          <label style={{ flexDirection: 'row', alignItems: 'center' }}><input type="checkbox" checked={f.failedOnly === 'true'} onChange={(e) => setF({ ...f, failedOnly: e.target.checked ? 'true' : '' })} />Failed only</label>
          <button type="submit">Filter</button>
        </form>
        <Alert error={error} />
        <table><thead><tr><th>Time</th><th>Username</th><th>Outcome</th><th>IP</th><th>Client</th></tr></thead>
          <tbody>{data?.content.map((l) => <tr key={l.id}><td>{when(l.createdAt)}</td><td>{l.username}</td><td><StatusBadge s={l.outcome} /></td><td>{l.ipAddress}</td><td className="muted" style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{l.userAgent}</td></tr>)}</tbody></table>
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}

interface Summary { transactionCount: number; completedCount: number; failedCount: number; volume: string; depositTotal: string; withdrawalTotal: string; transferTotal: string }

export function ReportsPage() {
  const [period, setPeriod] = useState('daily')
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10))
  const [r, setR] = useState<{ from: string; to: string; summary: Summary; breakdown: ({ period: string } & Summary)[]; notice: string }>()
  const [top, setTop] = useState<{ accounts: { accountNumber: string; count: number; volume: string }[] }>()
  const [error, setError] = useState<string>()
  const run = async (e?: FormEvent) => {
    e?.preventDefault()
    try {
      setR((await api.get('/admin/reports', { params: { period, date } })).data)
      setTop((await api.get('/admin/analytics/top-accounts', { params: { limit: 5 } })).data)
      setError(undefined)
    } catch (err) { setError(errorMessage(err)) }
  }
  useEffect(() => { run() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <>
      <h1>Reports <span className="badge">Python analytics</span></h1>
      <div className="card">
        <form className="inline" onSubmit={run}>
          <label>Period<select value={period} onChange={(e) => setPeriod(e.target.value)}><option value="daily">Daily</option><option value="weekly">Weekly</option><option value="monthly">Monthly</option></select></label>
          <label>Date<input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></label>
          <button type="submit">Generate</button>
        </form>
      </div>
      <Alert error={error} />
      {r && <>
        <div className="grid">
          <Stat label={`Transactions ${r.from} – ${r.to}`} value={`${r.summary.transactionCount} (${r.summary.failedCount} failed)`} />
          <Stat label="Volume" value={inr(r.summary.volume)} />
          <Stat label="Deposits" value={inr(r.summary.depositTotal)} />
          <Stat label="Withdrawals" value={inr(r.summary.withdrawalTotal)} />
          <Stat label="Transfers" value={inr(r.summary.transferTotal)} />
        </div>
        <div className="card"><h2>Daily breakdown</h2>
          <table><thead><tr><th>Date</th><th className="num">Completed</th><th className="num">Failed</th><th className="num">Deposits</th><th className="num">Withdrawals</th><th className="num">Transfers</th></tr></thead>
            <tbody>{r.breakdown.map((b) => <tr key={b.period}><td>{b.period}</td><td className="num">{b.completedCount}</td><td className="num">{b.failedCount}</td><td className="num">{inr(b.depositTotal)}</td><td className="num">{inr(b.withdrawalTotal)}</td><td className="num">{inr(b.transferTotal)}</td></tr>)}</tbody></table>
          <p className="muted">{r.notice}</p></div>
      </>}
      {top && <div className="card"><h2>Most active accounts, last 30 days</h2>
        <table><thead><tr><th>Account</th><th className="num">Transactions</th><th className="num">Volume</th></tr></thead>
          <tbody>{top.accounts.map((a) => <tr key={a.accountNumber}><td className="mono">{a.accountNumber}</td><td className="num">{a.count}</td><td className="num">{inr(a.volume)}</td></tr>)}</tbody></table></div>}
    </>
  )
}
