import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TransferView } from '@/api/types'
import { ApprovalPanel } from './approval-panel'

const view: TransferView = {
  transfer: {
    id: 't-1',
    status: 'RUNNING',
    terms: { sourceAccountId: 'ACC-1001', destinationAccountId: 'ACC-2002', amount: { amount: 150, currency: 'USD' } },
    reason: null,
    createdAt: '2026-11-09T12:00:00Z',
  },
  steps: [],
  events: [],
}

const owners: Record<string, string> = { 'ACC-1001': 'Alice', 'ACC-2002': 'Bob' }

describe('ApprovalPanel', () => {
  afterEach(cleanup)

  it.each([
    ['Approve', true],
    ['Decline', false],
  ])('%s sends the decision once and blocks a second click', async (button, approved) => {
    const onDecide = vi.fn(() => new Promise<void>((resolve) => setTimeout(resolve, 10)))
    render(<ApprovalPanel view={view} name={(id) => owners[id]} onDecide={onDecide} />)

    expect(screen.getByText(/\$150\.00 is debited from Alice and held/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: button }))

    expect(onDecide).toHaveBeenCalledWith(approved)
    expect(screen.getByRole('button', { name: button })).toBeDisabled()
    await waitFor(() => expect(screen.getByRole('button', { name: button })).toBeEnabled())
  })
})
