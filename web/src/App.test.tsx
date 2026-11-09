import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Scenario, TransferSummary, TransferView } from '@/api/types'
import { TooltipProvider } from '@/components/ui/tooltip'
import App from './App'

const scenarios: Scenario[] = [
  {
    id: 'lost-reply',
    title: 'Lost reply',
    proves: 'Money moves once.',
    request: {
      sourceAccountId: 'ACC-1001',
      destinationAccountId: 'ACC-2002',
      amount: 40,
      currency: 'USD',
      faults: [
        {
          step: 'DEBIT_SOURCE',
          action: 'EXECUTE',
          kind: 'LOSE_REPLY',
          times: 1,
        },
      ],
    },
    expected: 'COMPLETED',
  },
  {
    id: 'happy-path',
    title: 'Happy path',
    proves: 'Four steps, two services.',
    request: {
      sourceAccountId: 'ACC-1001',
      destinationAccountId: 'ACC-2002',
      amount: 120,
      currency: 'USD',
      faults: [],
    },
    expected: 'COMPLETED',
  },
]

const lastRun: TransferSummary = {
  id: 'run-1',
  status: 'COMPLETED',
  terms: {
    sourceAccountId: 'ACC-1001',
    destinationAccountId: 'ACC-2002',
    amount: { amount: 40, currency: 'USD' },
  },
  reason: null,
  createdAt: '2026-11-09T18:00:00Z',
}

const lastRunView: TransferView = {
  transfer: lastRun,
  steps: [],
  events: [
    {
      id: 1,
      at: lastRun.createdAt,
      type: 'COMPLETED',
      step: null,
      action: null,
      attempt: 1,
      detail: null,
    },
  ],
}

class FakeEventSource {
  addEventListener(_: string, listener: (message: { data: string }) => void) {
    queueMicrotask(() => listener({ data: JSON.stringify(lastRunView) }))
  }
  close() {}
}

const responses: Record<string, unknown> = {
  '/api/scenarios': scenarios,
  '/api/accounts': [
    {
      id: 'ACC-1001',
      ownerName: 'Alice Martins',
      currency: 'USD',
      balance: 10000,
      status: 'ACTIVE',
    },
    {
      id: 'ACC-2002',
      ownerName: 'Bob Silva',
      currency: 'USD',
      balance: 2500,
      status: 'ACTIVE',
    },
  ],
  '/api/limits': [],
  '/api/transfers?limit=6': [lastRun],
}

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string) => new Response(JSON.stringify(responses[path]), { status: 200 })),
    )
    vi.stubGlobal('EventSource', FakeEventSource)
    Element.prototype.scrollTo = () => {}
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('shows the selected scenario with its owners and fault', async () => {
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    )

    expect(await screen.findByRole('heading', { name: 'Lost reply' })).toBeInTheDocument()
    expect(await screen.findByText('Alice Martins')).toBeInTheDocument()
    expect(screen.getByText('Bob Silva')).toBeInTheDocument()
    expect(screen.getByText('Lost reply on debit source × 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /run scenario/i })).toBeEnabled()
  })

  it('closes the open run when another scenario is chosen and keeps it in recent runs', async () => {
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    )
    const history = await screen.findByRole('list', { name: 'Recent runs' })

    fireEvent.click(within(history).getByRole('button'))
    expect(await screen.findByText('Progress')).toBeInTheDocument()

    fireEvent.click(
      within(screen.getByRole('list', { name: 'Scenarios' })).getByRole('button', { name: /happy path/i }),
    )
    expect(screen.queryByText('Progress')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Happy path' })).toBeInTheDocument()
    expect(within(history).getByRole('button')).toBeInTheDocument()
  })
})
