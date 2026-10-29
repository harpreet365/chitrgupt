import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage, errorReference, inr, newIdempotencyKey, type Account } from '../api'
import { useAuth } from '../auth'
import { Alert, Stat, StatusBadge, TransactionTable } from '../components'

export function useMyAccounts() {
  const [accounts, setAccounts] = useState<Account[]>([])
  const reload = () => api.get('/accounts').then((r) => setAccounts(r.data.content)).catch(() => setAccounts([]))
  useEffect(() => { reload() }, [])
  return { accounts, reload }
}

/**
 * Deposit / withdraw / transfer form used by customers (pick own account) and employees (type account numbers).
 * One idempotency key per form submission: a double-click or network retry cannot move money twice.
 */
export function MoneyForm({ accounts, onDone }: { accounts?: Account[]; onDone?: () => void }) {
  const [kind, setKind] = useState<'deposits' | 'withdrawals' | 'transfers'>('deposits')
  const [acct, setAcct] = useState('')
  const [dest, setDest] = useState('')
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [key, setKey] = useState(newIdempotencyKey)
  const [msg, setMsg] = useState<{ ok?: string; error?: string; ref?: string }>({})
  useEffect(() => { if (accounts?.length && !acct) setAcct(accounts[0].accountNumber) }, [accounts, acct])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) { setMsg({ error: 'Enter a positive amount with at most 2 decimals.' }); return }
    if (!/^\d{12}$/.test(acct) || (kind === 'transfers' && !/^\d{12}$/.test(dest))) { setMsg({ error: 'Account numbers have 12 digits.' }); return }
    setBusy(true); setMsg({})
    const body = kind === 'transfers'
      ? { sourceAccountNumber: acct, destinationAccountNumber: dest, amount, description: description || undefined }
      : { accountNumber: acct, amount, description: description || undefined }
    try {
      const { data } = await api.post(`/transactions/${kind}`, body, { headers: { 'Idempotency-Key': key } })
      setMsg({ ok: `${data.type} completed. New balance: ${inr(data.balanceAfter)}.`, ref: data.reference })
      setAmount(''); setDescription(''); setDest('')
      setKey(newIdempotencyKey())
      onDone?.()
    } catch (err) {
      setMsg({ error: errorMessage(err), ref: errorReference(err) })
      setKey(newIdempotencyKey())
    } finally { setBusy(false) }
  }

  return (
    <div className="card">
      <h2>Move money <span className="badge">simulated funds</span></h2>
      <div className="row" style={{ marginBottom: 12 }} role="tablist">
        {(['deposits', 'withdrawals', 'transfers'] as const).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={kind === k} className={kind === k ? '' : 'secondary'} onClick={() => { setKind(k); setMsg({}) }}>
            {k === 'deposits' ? 'Deposit' : k === 'withdrawals' ? 'Withdraw' : 'Transfer'}</button>
        ))}
      </div>
      {msg.error && <div className="alert error" role="alert">{msg.error}{msg.ref && <> (reference <Link to={`/transactions/${msg.ref}`}>{msg.ref}</Link>)</>}</div>}
      {msg.ok && <div className="alert ok" role="status">{msg.ok} Receipt: <Link to={`/transactions/${msg.ref}`}>{msg.ref}</Link></div>}
      <form className="inline" onSubmit={submit}>
        <label>{kind === 'transfers' ? 'From account' : 'Account'}
          {accounts ? (
            <select value={acct} onChange={(e) => setAcct(e.target.value)}>
              {accounts.map((a) => <option key={a.id} value={a.accountNumber}>{a.accountNumber} · {a.accountType} · {inr(a.balance)}</option>)}
            </select>
          ) : <input value={acct} onChange={(e) => setAcct(e.target.value.trim())} placeholder="12-digit account no." inputMode="numeric" />}
        </label>
        {kind === 'transfers' && <label>To account<input value={dest} onChange={(e) => setDest(e.target.value.trim())} placeholder="12-digit account no." inputMode="numeric" /></label>}
        <label>Amount (₹)<input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" style={{ width: 120 }} /></label>
        <label>Description<input value={description} maxLength={140} onChange={(e) => setDescription(e.target.value)} /></label>
        <button type="submit" disabled={busy}>{busy ? 'Processing…' : 'Submit'}</button>
      </form>
    </div>
  )
}

export function CustomerDashboard() {
  const { me } = useAuth()
  const { accounts, reload } = useMyAccounts()
  const [summary, setSummary] = useState<{ moneyIn: string; moneyOut: string }>()
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!accounts[0]) return
    api.get(`/accounts/${accounts[0].id}/analytics`).then((r) => setSummary(r.data)).catch(() => setSummary(undefined))
  }, [accounts])
  const total = accounts.reduce((s, a) => s + Number(a.balance), 0)
  return (
    <>
      <h1>Welcome, {me?.fullName}</h1>
      <div className="grid">
        <Stat label="Total balance" value={inr(total)} />
        <Stat label="Accounts" value={accounts.length} />
        <Stat label="Money in, 30 days (primary)" value={summary ? inr(summary.moneyIn) : '-'} />
        <Stat label="Money out, 30 days (primary)" value={summary ? inr(summary.moneyOut) : '-'} />
      </div>
      <div className="card">
        <h2>My accounts</h2>
        <table>
          <thead><tr><th>Account</th><th>Type</th><th>Status</th><th className="num">Balance</th></tr></thead>
          <tbody>{accounts.map((a) => (
            <tr key={a.id}><td className="mono">{a.accountNumber}{a.demo && <span className="badge">demo</span>}</td><td>{a.accountType}</td>
              <td><StatusBadge s={a.status} /></td><td className="num">{inr(a.balance)}</td></tr>))}</tbody>
        </table>
      </div>
      <MoneyForm accounts={accounts} onDone={() => { reload(); setTick(tick + 1) }} />
      <TransactionTable key={tick} title="Recent transactions" showFilters={false} size={8} />
    </>
  )
}

export function CustomerTransactions() {
  return <><h1>Transaction history</h1><TransactionTable /></>
}

export function CustomerProfile() {
  const { me, refresh } = useAuth()
  const p = me?.customerProfile
  const [f, setF] = useState({ email: me?.email ?? '', phone: p?.phone ?? '', addressLine: p?.addressLine ?? '', city: p?.city ?? '', postalCode: p?.postalCode ?? '' })
  const [msg, setMsg] = useState<{ ok?: string; error?: string }>({})
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    try {
      const body = Object.fromEntries(Object.entries(f).filter(([, v]) => v !== ''))
      await api.put('/me/profile', body); await refresh(); setMsg({ ok: 'Profile updated.' })
    } catch (err) { setMsg({ error: errorMessage(err) }) }
  }
  const field = (k: keyof typeof f, label: string) =>
    <label>{label}<input value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} /></label>
  return (
    <>
      <h1>My profile</h1>
      <div className="card">
        <p><b>{me?.fullName}</b> · Customer no. <span className="mono">{p?.customerNumber}</span> · Username <span className="mono">{me?.username}</span></p>
        <p className="muted">Name and identity details can only be changed at a branch.</p>
        <Alert {...msg} />
        <form className="inline" onSubmit={submit}>
          {field('email', 'Email')}{field('phone', 'Phone')}{field('addressLine', 'Address')}{field('city', 'City')}{field('postalCode', 'Postal code')}
          <button type="submit">Save</button>
        </form>
      </div>
    </>
  )
}
