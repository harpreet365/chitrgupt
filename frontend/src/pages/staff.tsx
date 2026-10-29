import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, errorMessage, inr, when, type Account } from '../api'
import { useAuth } from '../auth'
import { Alert, Pager, Stat, StatusBadge, TransactionTable, usePaged } from '../components'
import { MoneyForm } from './customer'

interface CustomerSummary { id: number; customerNumber: string; username: string; fullName: string; email: string; phone?: string; status: string; demo: boolean }

export function StaffDashboard() {
  const { me } = useAuth()
  const [d, setD] = useState<Record<string, number>>({})
  useEffect(() => { api.get('/staff/dashboard').then((r) => setD(r.data)).catch(() => {}) }, [])
  return (
    <>
      <h1>Branch dashboard</h1>
      <p className="muted">Your permissions: {me?.permissions.join(', ') || 'none'}</p>
      <div className="grid">
        <Stat label="My operations today" value={d.myOperationsToday ?? '-'} />
        <Stat label="My open flags" value={d.myOpenFlags ?? '-'} />
        <Stat label="Open flags (all)" value={d.openFlagsTotal ?? '-'} />
        <Stat label="Failed transactions, 24h" value={d.failedTransactions24h ?? '-'} />
        <Stat label="Suspended accounts" value={d.suspendedAccounts ?? '-'} />
      </div>
      <TransactionTable fixed={{ mine: 'true' }} title="My recent operations" showFilters={false} size={10} />
    </>
  )
}

export function CustomersPage() {
  const [q, setQ] = useState('')
  const [applied, setApplied] = useState('')
  const [page, setPage] = useState(0)
  const { data, error } = usePaged<CustomerSummary>('/customers', { q: applied, page, size: 15 })
  return (
    <>
      <h1>Customers</h1>
      <div className="card">
        <form className="inline" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(q) }} style={{ marginBottom: 12 }}>
          <label>Search name, username, email, customer no. or account no.<input value={q} onChange={(e) => setQ(e.target.value)} style={{ width: 320 }} /></label>
          <button type="submit">Search</button>
          <Link to="/staff/customers/new"><button type="button" className="secondary">Register customer</button></Link>
        </form>
        <Alert error={error} />
        <table>
          <thead><tr><th>Customer no.</th><th>Name</th><th>Username</th><th>Email</th><th>Status</th></tr></thead>
          <tbody>{data?.content.map((c) => (
            <tr key={c.id}><td className="mono"><Link to={`/staff/customers/${c.id}`}>{c.customerNumber}</Link></td>
              <td>{c.fullName}{c.demo && <span className="badge">demo</span>}</td><td>{c.username}</td><td>{c.email}</td><td><StatusBadge s={c.status} /></td></tr>))}</tbody>
        </table>
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}

interface CustomerDetail {
  customer: CustomerSummary; dateOfBirth?: string; addressLine?: string; city?: string; postalCode?: string
  lastLoginAt?: string; accounts: Account[]; recentLogins: { id: number; outcome: string; createdAt: string }[]
}

export function CustomerDetailPage() {
  const { id } = useParams()
  const [d, setD] = useState<CustomerDetail>()
  const [error, setError] = useState<string>()
  const [type, setType] = useState('SAVINGS')
  const [msg, setMsg] = useState<{ ok?: string; error?: string }>({})
  const load = () => api.get(`/customers/${id}`).then((r) => setD(r.data)).catch((e) => setError(errorMessage(e)))
  useEffect(() => { load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const openAccount = async (e: FormEvent) => {
    e.preventDefault()
    try { const r = await api.post(`/customers/${id}/accounts`, { accountType: type }); setMsg({ ok: `Opened ${r.data.accountNumber}` }); load() }
    catch (err) { setMsg({ error: errorMessage(err) }) }
  }
  if (error) return <Alert error={error} />
  if (!d) return <p className="muted">Loading…</p>
  const c = d.customer
  return (
    <>
      <h1>{c.fullName} <StatusBadge s={c.status} /></h1>
      <div className="card">
        <p>Customer no. <span className="mono">{c.customerNumber}</span> · {c.username} · {c.email} · {c.phone ?? 'no phone'}</p>
        <p className="muted">{[d.addressLine, d.city, d.postalCode].filter(Boolean).join(', ') || 'No address'} · DOB {d.dateOfBirth ?? '-'} · Last login {when(d.lastLoginAt)}</p>
      </div>
      <div className="card">
        <h2>Accounts</h2>
        <table><thead><tr><th>Account</th><th>Type</th><th>Status</th><th className="num">Balance</th></tr></thead>
          <tbody>{d.accounts.map((a) => <tr key={a.id}><td className="mono">{a.accountNumber}</td><td>{a.accountType}</td><td><StatusBadge s={a.status} /></td><td className="num">{inr(a.balance)}</td></tr>)}</tbody></table>
        <Alert {...msg} />
        <form className="inline" onSubmit={openAccount} style={{ marginTop: 12 }}>
          <label>New account<select value={type} onChange={(e) => setType(e.target.value)}><option>SAVINGS</option><option>CURRENT</option></select></label>
          <button type="submit" className="secondary">Open account</button>
        </form>
      </div>
      <TransactionTable fixed={{ customerId: c.id }} title="Customer transactions" />
    </>
  )
}

export function RegisterCustomerPage() {
  const [f, setF] = useState({ username: '', email: '', fullName: '', phone: '', dateOfBirth: '', addressLine: '', city: '', postalCode: '', initialAccountType: 'SAVINGS' })
  const [result, setResult] = useState<{ customer: CustomerSummary; accounts: Account[]; temporaryPassword: string }>()
  const [error, setError] = useState<string>()
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    try {
      const body = Object.fromEntries(Object.entries(f).filter(([, v]) => v !== ''))
      setResult((await api.post('/customers', body)).data); setError(undefined)
    } catch (err) { setError(errorMessage(err)) }
  }
  const field = (k: keyof typeof f, label: string, type = 'text') =>
    <label>{label}<input type={type} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} /></label>
  if (result) return (
    <div className="card">
      <h1>Customer registered</h1>
      <p>{result.customer.fullName} · <span className="mono">{result.customer.customerNumber}</span> · username <b>{result.customer.username}</b></p>
      {result.accounts.map((a) => <p key={a.id}>Account <span className="mono">{a.accountNumber}</span> ({a.accountType})</p>)}
      <div className="alert ok">Temporary password (shown once, hand it to the customer): <span className="mono">{result.temporaryPassword}</span>. They must change it at first sign-in.</div>
      <Link to={`/staff/customers/${result.customer.id}`}>Open customer record</Link>
    </div>
  )
  return (
    <>
      <h1>Register customer</h1>
      <div className="card">
        <Alert error={error} />
        <form className="inline" onSubmit={submit}>
          {field('fullName', 'Full name *')}{field('username', 'Username *')}{field('email', 'Email *', 'email')}
          {field('phone', 'Phone')}{field('dateOfBirth', 'Date of birth', 'date')}{field('addressLine', 'Address')}
          {field('city', 'City')}{field('postalCode', 'Postal code')}
          <label>Initial account<select value={f.initialAccountType} onChange={(e) => setF({ ...f, initialAccountType: e.target.value })}>
            <option value="SAVINGS">Savings</option><option value="CURRENT">Current</option><option value="">None</option></select></label>
          <button type="submit">Register</button>
        </form>
      </div>
    </>
  )
}

export function OperationsPage() {
  return (
    <>
      <h1>Branch operations</h1>
      <p className="muted">The server checks your permissions for each operation.</p>
      <MoneyForm />
      <TransactionTable title="All transactions" />
    </>
  )
}

export function FlagsPage() {
  const { me } = useAuth()
  const [status, setStatus] = useState('OPEN')
  const [page, setPage] = useState(0)
  const { data, error, reload } = usePaged<{ id: number; transactionReference: string; amount: number; type: string; source: string; reason: string; flaggedBy: string; status: string; reviewedBy?: string; reviewNote?: string; createdAt: string }>('/staff/flags', { status, page, size: 15 })
  const [msg, setMsg] = useState<{ ok?: string; error?: string }>({})
  const review = async (id: number, outcome: string) => {
    const note = window.prompt(`Note for ${outcome.toLowerCase()}:`) ?? ''
    try { await api.patch(`/admin/flags/${id}`, { status: outcome, note }); setMsg({ ok: 'Review saved.' }); reload() }
    catch (err) { setMsg({ error: errorMessage(err) }) }
  }
  return (
    <>
      <h1>{me?.role === 'ADMIN' ? 'Flagged transactions' : 'Flags I raised'}</h1>
      <div className="card">
        <label style={{ width: 160, marginBottom: 12 }}>Status<select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
          <option value="OPEN">Open</option><option value="RESOLVED">Resolved</option><option value="DISMISSED">Dismissed</option><option value="">All</option></select></label>
        <Alert error={error ?? msg.error} ok={msg.ok} />
        <table>
          <thead><tr><th>Raised</th><th>Transaction</th><th className="num">Amount</th><th>Source / reason</th><th>Status</th>{me?.role === 'ADMIN' && <th />}</tr></thead>
          <tbody>{data?.content.map((f) => (
            <tr key={f.id}><td>{when(f.createdAt)}</td><td className="mono"><Link to={`/transactions/${f.transactionReference}`}>{f.transactionReference}</Link><div className="muted">{f.type}</div></td>
              <td className="num">{inr(f.amount)}</td><td>{f.source} · {f.flaggedBy}<div className="muted">{f.reason}</div></td>
              <td><StatusBadge s={f.status} />{f.reviewNote && <div className="muted">{f.reviewedBy}: {f.reviewNote}</div>}</td>
              {me?.role === 'ADMIN' && <td>{f.status === 'OPEN' && <div className="row"><button onClick={() => review(f.id, 'RESOLVED')}>Resolve</button><button className="secondary" onClick={() => review(f.id, 'DISMISSED')}>Dismiss</button></div>}</td>}
            </tr>))}</tbody>
        </table>
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}

export function MyActivityPage() {
  const [page, setPage] = useState(0)
  const { data } = usePaged<{ id: number; action: string; targetType?: string; targetId?: string; details?: string; createdAt: string }>('/me/activity', { page, size: 20 })
  return (
    <>
      <h1>My activity log</h1>
      <div className="card">
        <table><thead><tr><th>Time</th><th>Action</th><th>Target</th><th>Details</th></tr></thead>
          <tbody>{data?.content.map((a) => <tr key={a.id}><td>{when(a.createdAt)}</td><td>{a.action}</td><td className="mono">{a.targetType}:{a.targetId}</td><td className="mono">{a.details}</td></tr>)}</tbody></table>
        <Pager page={data} onPage={setPage} />
      </div>
    </>
  )
}
