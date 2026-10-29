import { StrictMode, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import type { Role } from './api'
import { AuthProvider, homeFor, useAuth } from './auth'
import { Layout, TransactionTable } from './components'
import { AccountsPage, AdminOverview, AuditPage, LoginActivityPage, ReportsPage, UsersPage } from './pages/admin'
import { LoginPage, RegisterPage, SecurityPage, TransactionDetailPage } from './pages/common'
import { CustomerDashboard, CustomerProfile, CustomerTransactions } from './pages/customer'
import { CustomerDetailPage, CustomersPage, FlagsPage, MyActivityPage, OperationsPage, RegisterCustomerPage, StaffDashboard } from './pages/staff'
import './styles.css'

const NAV: Record<Role, [string, string][]> = {
  CUSTOMER: [['/customer', 'Dashboard'], ['/customer/transactions', 'Transactions'], ['/customer/profile', 'Profile'], ['/activity', 'Activity']],
  EMPLOYEE: [['/staff', 'Dashboard'], ['/staff/customers', 'Customers'], ['/staff/customers/new', 'Register customer'],
    ['/staff/operations', 'Operations'], ['/staff/flags', 'My flags'], ['/activity', 'My activity']],
  ADMIN: [['/admin', 'Overview'], ['/admin/users', 'Users & roles'], ['/admin/accounts', 'Accounts'], ['/staff/customers', 'Customers'],
    ['/admin/transactions', 'Transactions'], ['/admin/flags', 'Flagged'], ['/admin/reports', 'Reports'], ['/admin/audit', 'Audit log'], ['/admin/logins', 'Login activity']],
}

/** UI-level routing guard only; every API call is authorised again by the backend. */
function Protected({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <p className="muted" style={{ padding: 24 }}>Loading…</p>
  if (!me) return <Navigate to="/login" replace />
  if (me.mustChangePassword) return <Layout links={[]}><SecurityPage /></Layout>
  if (roles && !roles.includes(me.role)) return <Navigate to={homeFor(me.role)} replace />
  return <Layout links={NAV[me.role]}>{children}</Layout>
}

function Home() {
  const { me, loading } = useAuth()
  if (loading) return null
  return <Navigate to={me ? homeFor(me.role) : '/login'} replace />
}

const staff: Role[] = ['EMPLOYEE', 'ADMIN']

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/" element={<Home />} />
          <Route path="/security" element={<Protected><SecurityPage /></Protected>} />
          <Route path="/activity" element={<Protected><MyActivityPage /></Protected>} />
          <Route path="/transactions/:reference" element={<Protected><TransactionDetailPage /></Protected>} />
          <Route path="/customer" element={<Protected roles={['CUSTOMER']}><CustomerDashboard /></Protected>} />
          <Route path="/customer/transactions" element={<Protected roles={['CUSTOMER']}><CustomerTransactions /></Protected>} />
          <Route path="/customer/profile" element={<Protected roles={['CUSTOMER']}><CustomerProfile /></Protected>} />
          <Route path="/staff" element={<Protected roles={['EMPLOYEE']}><StaffDashboard /></Protected>} />
          <Route path="/staff/customers" element={<Protected roles={staff}><CustomersPage /></Protected>} />
          <Route path="/staff/customers/new" element={<Protected roles={staff}><RegisterCustomerPage /></Protected>} />
          <Route path="/staff/customers/:id" element={<Protected roles={staff}><CustomerDetailPage /></Protected>} />
          <Route path="/staff/operations" element={<Protected roles={['EMPLOYEE']}><OperationsPage /></Protected>} />
          <Route path="/staff/flags" element={<Protected roles={['EMPLOYEE']}><FlagsPage /></Protected>} />
          <Route path="/admin" element={<Protected roles={['ADMIN']}><AdminOverview /></Protected>} />
          <Route path="/admin/users" element={<Protected roles={['ADMIN']}><UsersPage /></Protected>} />
          <Route path="/admin/accounts" element={<Protected roles={['ADMIN']}><AccountsPage /></Protected>} />
          <Route path="/admin/transactions" element={<Protected roles={['ADMIN']}><h1>All transactions</h1><TransactionTable /></Protected>} />
          <Route path="/admin/flags" element={<Protected roles={['ADMIN']}><FlagsPage /></Protected>} />
          <Route path="/admin/reports" element={<Protected roles={['ADMIN']}><ReportsPage /></Protected>} />
          <Route path="/admin/audit" element={<Protected roles={['ADMIN']}><AuditPage /></Protected>} />
          <Route path="/admin/logins" element={<Protected roles={['ADMIN']}><LoginActivityPage /></Protected>} />
          <Route path="*" element={<Home />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
)

