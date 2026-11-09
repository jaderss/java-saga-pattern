import type { Account, LimitUsage, Scenario, TransferRequest, TransferSummary, TransferView } from './types'

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

const post = { method: 'POST' }

export const api = {
  scenarios: () => json<Scenario[]>('/api/scenarios'),
  accounts: () => json<Account[]>('/api/accounts'),
  limits: () => json<LimitUsage[]>('/api/limits'),
  recent: () => json<TransferSummary[]>('/api/transfers?limit=6'),
  transfer: (id: string) => json<TransferView>(`/api/transfers/${id}`),
  start: (request: TransferRequest) =>
    json<TransferView>('/api/transfers', {
      ...post,
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(request),
    }),
  reset: () =>
    Promise.all([json<Account[]>('/api/accounts/reset', post), json<LimitUsage[]>('/api/limits/reset', post)]),
  approve: (id: string) => json<TransferView>(`/api/transfers/${id}/approve`, post),
  decline: (id: string) => json<TransferView>(`/api/transfers/${id}/decline`, post),
  streamUrl: (id: string) => `/api/transfers/${id}/stream`,
}
