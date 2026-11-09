export type Step = 'RESERVE_LIMIT' | 'DEBIT_SOURCE' | 'APPROVE_TRANSFER' | 'CREDIT_DESTINATION' | 'CLEAR_TRANSFER'
export type Action = 'EXECUTE' | 'COMPENSATE'
export type SagaStatus = 'RUNNING' | 'COMPENSATING' | 'COMPLETED' | 'COMPENSATED' | 'REJECTED'
export type StepStatus =
  'PENDING' | 'IN_PROGRESS' | 'SUCCEEDED' | 'REJECTED' | 'TIMED_OUT' | 'COMPENSATING' | 'COMPENSATED'
export type SagaEventType =
  | 'STARTED'
  | 'COMMAND_SENT'
  | 'AWAITING_APPROVAL'
  | 'STEP_SUCCEEDED'
  | 'STEP_REJECTED'
  | 'STEP_COMPENSATED'
  | 'TIMED_OUT'
  | 'RETRIES_EXHAUSTED'
  | 'REPLY_IGNORED'
  | 'COMPLETED'
  | 'COMPENSATED'
  | 'REJECTED'
export type FaultKind = 'LOSE_REPLY' | 'CRASH_BEFORE_COMMIT'

export interface Fault {
  step: Step
  action: Action
  kind: FaultKind
  times: number
}

export interface TransferRequest {
  sourceAccountId: string
  destinationAccountId: string
  amount: number
  currency: string
  faults: Fault[]
  requiresApproval?: boolean
}

export interface Scenario {
  id: string
  title: string
  proves: string
  request: TransferRequest
  expected: SagaStatus
}

export interface TransferSummary {
  id: string
  status: SagaStatus
  terms: {
    sourceAccountId: string
    destinationAccountId: string
    amount: { amount: number; currency: string }
  }
  reason: string | null
  createdAt: string
}

export interface StepView {
  step: Step
  service: string
  status: StepStatus
}

export interface EventView {
  id: number
  at: string
  type: SagaEventType
  step: Step | null
  action: Action | null
  attempt: number
  detail: string | null
}

export interface TransferView {
  transfer: TransferSummary
  steps: StepView[]
  events: EventView[]
}

export interface Account {
  id: string
  ownerName: string
  currency: string
  balance: number
  status: 'ACTIVE' | 'FROZEN'
}

export interface LimitUsage {
  accountId: string
  day: string
  dailyLimit: number
  reserved: number
  used: number
  watchlistReason: string | null
}
