import type { SagaStatus, Step, StepStatus, TransferView } from '@/api/types'

export const STEP_LABELS: Record<Step, string> = {
  RESERVE_LIMIT: 'Reserve limit',
  DEBIT_SOURCE: 'Debit source',
  APPROVE_TRANSFER: 'Approve transfer',
  CREDIT_DESTINATION: 'Credit destination',
  CLEAR_TRANSFER: 'Clear transfer',
}

export const TERMINAL: ReadonlySet<SagaStatus> = new Set(['COMPLETED', 'COMPENSATED', 'REJECTED'])

export interface Progress {
  percent: number
  label: string
  tone: 'running' | 'rolling-back' | 'completed' | 'compensated' | 'rejected'
}

const count = (statuses: StepStatus[], ...wanted: StepStatus[]) => statuses.filter((s) => wanted.includes(s)).length

export function awaitingApproval(view: TransferView): boolean {
  return (
    view.transfer.status === 'RUNNING' &&
    view.steps.some((step) => step.step === 'APPROVE_TRANSFER' && step.status === 'IN_PROGRESS')
  )
}

export function progressOf(view: TransferView): Progress {
  const total = view.steps.length
  const statuses = view.steps.filter((step) => step.step !== 'APPROVE_TRANSFER').map((step) => step.status)
  switch (view.transfer.status) {
    case 'RUNNING': {
      const done = count(
        view.steps.map((step) => step.status),
        'SUCCEEDED',
      )
      const label = awaitingApproval(view)
        ? `Waiting for approval, step ${done + 1} of ${total}`
        : `Executing step ${done + 1} of ${total}`
      return { percent: (done / total) * 100, label, tone: 'running' }
    }
    case 'COMPENSATING': {
      const undone = count(statuses, 'COMPENSATED')
      const toUndo = undone + count(statuses, 'COMPENSATING', 'SUCCEEDED', 'TIMED_OUT')
      return {
        percent: (undone / toUndo) * 100,
        label: `Rolling back ${undone + 1} of ${toUndo}`,
        tone: 'rolling-back',
      }
    }
    case 'COMPLETED':
      return { percent: 100, label: 'Completed, every step applied', tone: 'completed' }
    case 'COMPENSATED': {
      const undone = count(statuses, 'COMPENSATED')
      return { percent: 100, label: `Rolled back ${undone} ${undone === 1 ? 'step' : 'steps'}`, tone: 'compensated' }
    }
    case 'REJECTED':
      return { percent: 100, label: 'Rejected, nothing to undo', tone: 'rejected' }
  }
}

export function attemptOf(view: TransferView, step: Step): number {
  return view.events
    .filter((event) => event.type === 'COMMAND_SENT' && event.step === step)
    .reduce((latest, event) => Math.max(latest, event.attempt), 0)
}

export function newer(current: TransferView | null, next: TransferView): TransferView {
  if (!current || current.transfer.id !== next.transfer.id) return next
  const lastId = (view: TransferView) => view.events.at(-1)?.id ?? 0
  return lastId(next) >= lastId(current) ? next : current
}
