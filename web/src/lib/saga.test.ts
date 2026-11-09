import { describe, expect, it } from 'vitest'
import type { StepStatus, TransferView } from '@/api/types'
import { awaitingApproval, newer, progressOf } from './saga'

const STEPS = ['RESERVE_LIMIT', 'DEBIT_SOURCE', 'CREDIT_DESTINATION', 'CLEAR_TRANSFER'] as const

function view(status: TransferView['transfer']['status'], statuses: StepStatus[], lastEventId = 1): TransferView {
  return {
    transfer: {
      id: 't-1',
      status,
      terms: { sourceAccountId: 'A', destinationAccountId: 'B', amount: { amount: 10, currency: 'USD' } },
      reason: null,
      createdAt: '2026-11-09T12:00:00Z',
    },
    steps: STEPS.map((step, i) => ({ step, service: 'risk-service', status: statuses[i] })),
    events: [{ id: lastEventId, at: '', type: 'STARTED', step: null, action: null, attempt: 0, detail: null }],
  }
}

describe('progressOf', () => {
  it('counts finished forward steps while running', () => {
    const progress = progressOf(view('RUNNING', ['SUCCEEDED', 'SUCCEEDED', 'IN_PROGRESS', 'PENDING']))

    expect(progress).toEqual({ percent: 50, label: 'Executing step 3 of 4', tone: 'running' })
  })

  it('says the saga waits for approval when the approval step is in progress', () => {
    const waiting = view('RUNNING', ['SUCCEEDED', 'SUCCEEDED', 'IN_PROGRESS', 'PENDING'])
    waiting.steps = [
      ...waiting.steps.slice(0, 2),
      { step: 'APPROVE_TRANSFER', service: 'transfer-service', status: 'IN_PROGRESS' },
      ...waiting.steps.slice(2).map((step) => ({ ...step, status: 'PENDING' as const })),
    ]

    expect(awaitingApproval(waiting)).toBe(true)
    expect(progressOf(waiting).label).toBe('Waiting for approval, step 3 of 5')
  })

  it('counts only the steps that need undoing while rolling back', () => {
    const progress = progressOf(view('COMPENSATING', ['SUCCEEDED', 'SUCCEEDED', 'COMPENSATED', 'REJECTED']))

    expect(progress.label).toBe('Rolling back 2 of 3')
    expect(progress.percent).toBeCloseTo(33.33, 1)
  })

  it('includes a step with an unknown outcome in the rollback', () => {
    const progress = progressOf(view('COMPENSATING', ['SUCCEEDED', 'SUCCEEDED', 'COMPENSATING', 'PENDING']))

    expect(progress.label).toBe('Rolling back 1 of 3')
  })

  it('reports a rejection with nothing to undo', () => {
    expect(progressOf(view('REJECTED', ['REJECTED', 'PENDING', 'PENDING', 'PENDING'])).label).toBe(
      'Rejected, nothing to undo',
    )
  })

  it('reports how many steps were rolled back', () => {
    const progress = progressOf(view('COMPENSATED', ['COMPENSATED', 'REJECTED', 'PENDING', 'PENDING']))

    expect(progress).toEqual({ percent: 100, label: 'Rolled back 1 step', tone: 'compensated' })
  })
})

describe('newer', () => {
  it('keeps the view with the latest event when updates arrive out of order', () => {
    const late = view('COMPLETED', ['SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED'], 9)
    const stale = view('RUNNING', ['SUCCEEDED', 'IN_PROGRESS', 'PENDING', 'PENDING'], 4)

    expect(newer(late, stale)).toBe(late)
    expect(newer(stale, late)).toBe(late)
  })
})
