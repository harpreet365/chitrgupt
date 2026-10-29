import axios, { AxiosError } from 'axios'

// Session cookie (HttpOnly) + CSRF: axios copies the XSRF-TOKEN cookie into the X-XSRF-TOKEN header.
export const api = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

export type Role = 'ADMIN' | 'EMPLOYEE' | 'CUSTOMER'

export interface Me {
  id: number
  username: string
  email: string
  fullName: string
  role: Role
  permissions: string[]
  mustChangePassword: boolean
  previousLoginAt?: string
  lastFailedLoginAt?: string
  demo: boolean
  customerProfile?: { customerNumber: string; phone?: string; addressLine?: string; city?: string; postalCode?: string }
}

export interface Account {
  id: number
  accountNumber: string
  accountType: string
  currency: string
  balance: number
  status: string
  customerId: number
  customerNumber: string
  holderName: string
  demo: boolean
}

export interface Txn {
  id: number
  reference: string
  type: string
  status: string
  amount: number
  sourceAccountNumber?: string
  destinationAccountNumber?: string
  direction?: string
  channel: string
  description?: string
  failureReason?: string
  initiatedBy: string
  demo: boolean
  flagged: boolean
  createdAt: string
}

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

/** Safe, user-facing message from an API error (the server already sends generic messages). */
export function errorMessage(e: unknown): string {
  const err = e as AxiosError<{ message?: string; fieldErrors?: Record<string, string> }>
  const data = err.response?.data
  if (data?.fieldErrors) {
    return Object.entries(data.fieldErrors).map(([k, v]) => `${k}: ${v}`).join('; ')
  }
  return data?.message ?? 'Unable to reach the server. Please try again.'
}

export function errorReference(e: unknown): string | undefined {
  return (e as AxiosError<{ reference?: string }>).response?.data?.reference
}

export const inr = (n: number | string | undefined) =>
  n === undefined || n === null ? '-' :
    new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(Number(n))

export const when = (s?: string) => (s ? new Date(s).toLocaleString('en-IN') : '-')

export const newIdempotencyKey = () => crypto.randomUUID()
