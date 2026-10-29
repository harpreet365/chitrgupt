# BMS Bank — Banking Management System (MVP)

> **Educational banking simulation.** Not connected to any payment network; no real money is processed.
> A production banking deployment would need an independent security review, regulatory assessment,
> operational controls and further hardening. No compliance claims are made.

| Part | Tech | Port |
|---|---|---|
| `backend-java/` | Java 25, Spring Boot 3.5, Spring Security (server sessions + CSRF), JPA, Flyway | 8080 (mgmt 8081) |
| `analytics-python/` | Python 3.11, FastAPI, Pydantic — read-only reports/statistics | 8090 |
| `frontend/` | React 19 + TypeScript + Vite, plain CSS | 5173 |
| Database | PostgreSQL 17 (embedded binary by default, or Docker Compose) | 55432 / 5432 |

## Quick start (Windows PowerShell)

Prerequisites: **Java 25+**, **Node.js 20+**, **Python 3.11+**. Docker is optional.

```powershell
# extract to a SHORT path, e.g. C:\bms  (deep paths can exceed Windows' 260-char limit)
Set-ExecutionPolicy -Scope Process Bypass
.\setup.ps1          # checks tools, creates .env with random secrets, builds everything
.\start.ps1          # starts DB + backend + analytics + frontend, waits for health checks
# open http://127.0.0.1:5173
.\stop.ps1           # graceful shutdown
```

`setup.ps1` prints the logins (they are stored in the git-ignored `.env`):

- **Admin**: `admin` / `BOOTSTRAP_ADMIN_PASSWORD`. You must change it at first sign-in.
- **Demo users** (password `DEMO_PASSWORD`): `employee.demo` (all permissions), `teller.demo` (no withdrawals/transfers),
  customers `aarav.demo`, `priya.demo`, `rohan.demo`, `ananya.demo`, `vikram.demo`, `meera.demo`.
  Demo users, accounts and ~60 days of transactions are labelled *demo*. They are generated through the real
  banking code with a fixed random seed (`DEMO_SEED=true`).

**Database options** (`.env`): `DB_MODE=embedded` (default) runs a real PostgreSQL server binary managed by the
backend, with data in `data/postgres`. For Docker, set `DB_MODE=external`, `USE_DOCKER_DB=true` and
`DB_URL=jdbc:postgresql://localhost:5432/bms`; `start.ps1` then runs `docker compose up`.

## Tests

```powershell
.\setup.ps1 -RunTests     # or individually:
cd backend-java; .\mvnw.cmd test                      # 29 integration tests on real PostgreSQL
cd analytics-python; .\.venv\Scripts\python -m pytest # 3 tests
```

The Java tests run the whole app over HTTP with real cookies and CSRF. They cover login/lockout/logout/session
revocation, password change, cross-customer access (404), privilege escalation (403), employee permissions,
admins being unable to move money, rejected mass-assignment fields, deposits, withdrawals, overdraft refusal,
transfers with balanced ledger entries, full rollback, idempotent retries (sequential and concurrent),
25 concurrent withdrawals racing on one account, opposing concurrent transfers (no deadlock),
append-only ledger triggers, history filters and pagination, and flagging.

## Roles

- **Customer**: own accounts, balances, history with filters, deposit (simulated)/withdraw/transfer, receipts,
  profile contact details, password change, own sign-in activity.
- **Employee**: customer search, registration (one-time temporary password), account opening, branch
  deposits/withdrawals/transfers, flagging. Each sensitive operation needs a permission granted by an admin.
- **Admin**: metrics, Python trend chart, users/roles/permissions, suspend/activate users and accounts,
  audit log, login activity, flag review, daily/weekly/monthly reports, ledger reconciliation.
  Admins cannot move money or edit balances.

## Key design points

- **Money**: `NUMERIC(19,2)` / `BigDecimal`, INR only. `balance >= 0` is a DB constraint (no overdraft).
- **Ledger**: every completed movement writes `ledger_entries`; a transfer writes one DEBIT and one CREDIT.
  Invariant: `balance = Σ credits − Σ debits` (checked by *Admin → Overview → Run check*).
  `transactions`, `ledger_entries`, `audit_logs` and `login_activity` are append-only (DB triggers).
- **Concurrency**: one DB transaction per operation. Account rows are locked with `SELECT … FOR UPDATE` in
  ascending id order, and balances change only through guarded `UPDATE … WHERE balance >= ?` statements.
  JPA cannot write the balance column.
- **Idempotency**: transfers require an `Idempotency-Key`. Retries return the original receipt; reusing a key
  for a different request returns 422.
- **Failures** (insufficient funds, inactive account, daily limit) roll back fully, then a `FAILED` transaction
  (no ledger entries) is recorded for traceability.
- **Auth**: BCrypt(12); server-side sessions in PostgreSQL (Spring Session JDBC), HttpOnly + SameSite=Strict
  cookie, CSRF token. Logins give one generic error, lock temporarily after 5 failures, and are rate-limited.
  Every request re-checks user status and role, so suspension or a password change revokes sessions immediately.
- **Python** never touches the database. It reads a minimal feed from `/internal/v1` (token + loopback only).
  The browser never calls it directly: Java checks permissions first, then proxies to it.
- Schema: `backend-java/src/main/resources/db/migration` (Flyway).

## API (prefix `/api/v1`)

`auth/{csrf,session,login,logout,password}` · `me`, `me/profile`, `me/login-activity`, `me/activity` ·
`accounts`, `accounts/{id}`, `accounts/{id}/analytics` · `customers` (search/register/detail/update),
`customers/{id}/accounts` · `transactions` (filters: type, status, from, to, minAmount, maxAmount, accountId,
reference; page/size/sort), `transactions/{ref}`, `transactions/{deposits|withdrawals|transfers}`,
`transactions/{ref}/flags` · `staff/dashboard`, `staff/flags` · `admin/{stats,users,accounts/{id}/status,
login-activity,audit-logs,flags/{id},reconciliation,reports,analytics/*}` · `health`.
Errors always use `{status, code, message, fieldErrors?, reference?}`.

## Troubleshooting

- **"running scripts is disabled"**: run `Set-ExecutionPolicy -Scope Process Bypass` first.
- **Port in use** (8080/8081/8090/5173/55432): run `.\stop.ps1`, or change ports in `.env`.
- **Backend not healthy**: see `logs\backend.log`. The first start downloads nothing, but initialising the
  embedded database takes about 20 s.
- **Analytics "down"**: see `logs\analytics.err.log`. Reports show a clear "service unavailable" message
  while the rest of the app keeps working.
- **Reset all data**: `.\stop.ps1`, delete the `data\` folder, then `.\start.ps1` (demo data is re-seeded).
- **Forgot admin password**: another admin can reset it. Otherwise reset the data as above.

## MVP limits (deliberately out of scope)

Single instance only (in-memory rate limiter), no email/OTP/2FA, no interest/statements/PDF export,
no Postman collection (the integration tests serve as the executable API suite), INR only, no overdrafts.
