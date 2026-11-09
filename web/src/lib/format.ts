import type { EventView, Fault, SagaStatus } from '@/api/types'
import { STEP_LABELS } from './saga'

export function money(amount: number, currency = 'USD') {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount)
}

export function signedMoney(amount: number, currency = 'USD') {
  if (amount === 0) return money(0, currency)
  return `${amount > 0 ? '+' : '−'}${money(Math.abs(amount), currency)}`
}

export function clock(iso: string) {
  const date = new Date(iso)
  return `${date.toLocaleTimeString('en-GB', { hour12: false })}.${String(date.getMilliseconds()).padStart(3, '0')}`
}

export const STATUS_LABELS: Record<SagaStatus, string> = {
  RUNNING: 'Running',
  COMPENSATING: 'Compensating',
  COMPLETED: 'Completed',
  COMPENSATED: 'Compensated',
  REJECTED: 'Rejected',
}

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'timeout'

export function describe(event: EventView): { title: string; tone: Tone } {
  const step = event.step ? STEP_LABELS[event.step] : ''
  const verb = event.action === 'COMPENSATE' ? 'undo' : 'run'
  if (event.step === 'APPROVE_TRANSFER') {
    if (event.type === 'AWAITING_APPROVAL') return { title: 'Waiting for your approval', tone: 'info' }
    if (event.type === 'STEP_SUCCEEDED') return { title: 'Approved', tone: 'success' }
    if (event.type === 'STEP_REJECTED')
      return { title: event.detail === 'declined' ? 'Declined' : 'Approval expired', tone: 'danger' }
  }
  switch (event.type) {
    case 'STARTED':
      return { title: 'Saga started', tone: 'neutral' }
    case 'COMMAND_SENT':
      return event.attempt > 1
        ? { title: `${step}: ${verb} resent, attempt ${event.attempt}`, tone: 'timeout' }
        : { title: `${step}: ${verb} sent`, tone: event.action === 'COMPENSATE' ? 'warning' : 'info' }
    case 'AWAITING_APPROVAL':
      return { title: `${step}: waiting for a decision`, tone: 'info' }
    case 'STEP_SUCCEEDED':
      return { title: `${step} succeeded`, tone: 'success' }
    case 'STEP_REJECTED':
      return { title: `${step} rejected`, tone: 'danger' }
    case 'STEP_COMPENSATED':
      return { title: `${step} undone`, tone: 'warning' }
    case 'TIMED_OUT':
      return { title: `${step}: no reply on attempt ${event.attempt}`, tone: 'timeout' }
    case 'RETRIES_EXHAUSTED':
      return { title: `${step}: retries exhausted, outcome unknown`, tone: 'danger' }
    case 'REPLY_IGNORED':
      return { title: `${step}: late reply ignored`, tone: 'neutral' }
    case 'COMPLETED':
      return { title: 'Transfer completed', tone: 'success' }
    case 'COMPENSATED':
      return { title: 'Transfer rolled back', tone: 'warning' }
    case 'REJECTED':
      return { title: 'Transfer rejected', tone: 'danger' }
  }
}

export const TONE_TEXT: Record<Tone, string> = {
  neutral: 'text-muted-foreground',
  info: 'text-info',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-destructive',
  timeout: 'text-timeout',
}

export const TONE_BG: Record<Tone, string> = {
  neutral: 'bg-muted-foreground',
  info: 'bg-info',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-destructive',
  timeout: 'bg-timeout',
}

export const STATUS_TONE: Record<SagaStatus, Tone> = {
  RUNNING: 'info',
  COMPENSATING: 'warning',
  COMPLETED: 'success',
  COMPENSATED: 'warning',
  REJECTED: 'danger',
}

export function faultLabel(fault: Fault) {
  const what = fault.kind === 'LOSE_REPLY' ? 'Lost reply' : 'Crash'
  const where = `${fault.action === 'COMPENSATE' ? 'undo ' : ''}${STEP_LABELS[fault.step].toLowerCase()}`
  return `${what} on ${where} × ${fault.times}`
}
