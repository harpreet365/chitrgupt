import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { NavLink, Link } from 'react-router-dom'
import { api, errorMessage, inr, when, type Page, type Txn } from './api'
import { useAuth } from './auth'

export function Layout({ links, children }: { links: [string, string][]; children: ReactNode }) {
  const { me, logout } = useAuth()
  return (
    <>
      <div className="banner">Educational banking simulation - no real money, no real payment networks.</div>
      <div className="shell">
        <nav className="side" aria-label="Main">
          <div className="brand">BMS Bank</div>
          {links.map(([to, label]) => <NavLink key={to} to={to} end>{label}</NavLink>)}
          <NavLink to="/security">Security</NavLink>
          <a href="#" onClick={(e) => { e.preventDefault(); logout() }}>Sign out</a>
          <div className="who">{me?.fullName}<br />{me?.role}{me?.demo && ' · demo'}</div>
        </nav>
        <main className="main">{children}</main>
      </div>
    </>
  )
}

export const Alert = ({ error, ok }: { error?: string; ok?: string }) =>
  error ? <div className="alert error" role="alert">{error}</div> : ok ? <div className="alert ok" role="status">{ok}</div> : null

export const Stat = ({ label, value }: { label: string; value: ReactNode }) =>
  <div className="stat"><div className="label">{label}</div><div className="value">{value}</div></div>

export const StatusBadge = ({ s }: { s: string }) =>
  <span className={'badge ' + (['ACTIVE', 'COMPLETED', 'SUCCESS', 'RESOLVED'].includes(s) ? 'ok' : ['FAILED', 'SUSPENDED', 'BAD_CREDENTIALS', 'LOCKED', 'OPEN'].includes(s) ? 'bad' : '')}>{s}</span>

/** Loads a server-paginated list; re-fetches whenever the query string changes. */
export function usePaged<T>(path: string, query: Record<string, string | number | undefined>) {
  const [data, setData] = useState<Page<T> | null>(null)
  const [error, setError] = useState<string>()
  const key = JSON.stringify(query)
  const load = useCallback(async () => {
    try {
      const params = Object.fromEntries(Object.entries(query).filter(([, v]) => v !== '' && v !== undefined))
      setData((await api.get(path, { params })).data)
      setError(undefined)
    } catch (e) { setError(errorMessage(e)) }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, key])
  useEffect(() => { load() }, [load])
  return { data, error, reload: load }
}

export function Pager({ page, onPage }: { page: Page<unknown> | null; onPage: (p: number) => void }) {
  if (!page || page.totalPages <= 1) return null
  return (
    <div className="row" style={{ marginTop: 12 }}>
      <button className="secondary" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</button>
      <span className="muted">Page {page.page + 1} of {page.totalPages} ({page.totalElements} items)</span>
      <button className="secondary" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>Next</button>
    </div>
  )
}

/** Transaction history with filters + pagination; used by every role (the server scopes the results). */
export function TransactionTable({ fixed = {}, title = 'Transactions', showFilters = true, size = 15 }:
  { fixed?: Record<string, string | number>; title?: string; showFilters?: boolean; size?: number }) {
  const [f, setF] = useState({ type: '', status: '', from: '', to: '', minAmount: '', maxAmount: '', reference: '' })
  const [applied, setApplied] = useState(f)
  const [page, setPage] = useState(0)
  const { data, error } = usePaged<Txn>('/transactions', { ...applied, ...fixed, page, size })
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  return (
    <div className="card">
      <h2>{title}</h2>
      {showFilters && (
        <form className="inline" style={{ marginBottom: 12 }} onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(f) }}>
          <label>Type<select value={f.type} onChange={set('type')}><option value="">All</option><option>DEPOSIT</option><option>WITHDRAWAL</option><option>TRANSFER</option></select></label>
          <label>Status<select value={f.status} onChange={set('status')}><option value="">All</option><option>COMPLETED</option><option>FAILED</option></select></label>
          <label>From<input type="date" value={f.from} onChange={set('from')} /></label>
          <label>To<input type="date" value={f.to} onChange={set('to')} /></label>
          <label>Min ₹<input type="number" min="0" step="0.01" value={f.minAmount} onChange={set('minAmount')} style={{ width: 100 }} /></label>
          <label>Max ₹<input type="number" min="0" step="0.01" value={f.maxAmount} onChange={set('maxAmount')} style={{ width: 100 }} /></label>
          <label>Reference<input value={f.reference} onChange={set('reference')} style={{ width: 170 }} /></label>
          <button type="submit">Filter</button>
        </form>
      )}
      <Alert error={error} />
      {data && data.content.length === 0 && <p className="muted">No transactions found.</p>}
      {data && data.content.length > 0 && (
        <table>
          <thead><tr><th>Date</th><th>Reference</th><th>Type</th><th>From → To</th><th>Status</th><th className="num">Amount</th></tr></thead>
          <tbody>
            {data.content.map((t) => (
              <tr key={t.id}>
                <td>{when(t.createdAt)}</td>
                <td className="mono"><Link to={`/transactions/${t.reference}`}>{t.reference}</Link>
                  {t.demo && <span className="badge">demo</span>}{t.flagged && <span className="badge warn">flagged</span>}</td>
                <td>{t.type}<div className="muted">{t.description}</div></td>
                <td className="mono">{t.sourceAccountNumber ?? 'cash'} → {t.destinationAccountNumber ?? 'cash'}</td>
                <td><StatusBadge s={t.status} />{t.failureReason && <div className="muted">{t.failureReason}</div>}</td>
                <td className={'num ' + (t.direction === 'DEBIT' ? 'debit' : t.direction === 'CREDIT' ? 'credit' : '')}>
                  {t.direction === 'DEBIT' ? '−' : t.direction === 'CREDIT' ? '+' : ''}{inr(t.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pager page={data} onPage={setPage} />
    </div>
  )
}
