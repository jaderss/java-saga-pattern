import { Check, CircleDashed, Clock, Hourglass, LoaderCircle, RotateCcw, Undo2, X } from 'lucide-react'
import type { StepStatus, StepView, TransferView } from '@/api/types'
import { Progress } from '@/components/ui/progress'
import { attemptOf, progressOf, STEP_LABELS } from '@/lib/saga'
import { cn } from '@/lib/utils'

const SEGMENT: Record<StepStatus, string> = {
  PENDING: 'bg-muted',
  IN_PROGRESS: 'bg-info animate-pulse',
  SUCCEEDED: 'bg-success',
  REJECTED: 'bg-destructive',
  TIMED_OUT: 'bg-timeout',
  COMPENSATING: 'bg-warning animate-pulse',
  COMPENSATED: 'hatched',
}

const STATUS_TEXT: Record<StepStatus, { label: string; icon: typeof Check; className: string }> = {
  PENDING: { label: 'Pending', icon: CircleDashed, className: 'text-muted-foreground' },
  IN_PROGRESS: { label: 'Running', icon: LoaderCircle, className: 'text-info [&>svg]:animate-spin' },
  SUCCEEDED: { label: 'Applied', icon: Check, className: 'text-success' },
  REJECTED: { label: 'Rejected', icon: X, className: 'text-destructive' },
  TIMED_OUT: { label: 'Unknown', icon: Clock, className: 'text-timeout' },
  COMPENSATING: { label: 'Undoing', icon: RotateCcw, className: 'text-warning [&>svg]:animate-spin' },
  COMPENSATED: { label: 'Undone', icon: Undo2, className: 'text-warning' },
}

const WAITING = { label: 'Waiting for you', icon: Hourglass, className: 'text-info' }

const textOf = (step: StepView) =>
  step.step === 'APPROVE_TRANSFER' && step.status === 'IN_PROGRESS' ? WAITING : STATUS_TEXT[step.status]

const BAR_TONE = {
  running: '[&>[data-slot=progress-indicator]]:bg-info',
  'rolling-back': '[&>[data-slot=progress-indicator]]:bg-warning',
  completed: '[&>[data-slot=progress-indicator]]:bg-success',
  compensated: '[&>[data-slot=progress-indicator]]:bg-warning',
  rejected: '[&>[data-slot=progress-indicator]]:bg-destructive',
}

export function StepTrack({ view }: { view: TransferView }) {
  const progress = progressOf(view)
  return (
    <div className="space-y-6">
      <ol
        className={cn('grid grid-cols-2 gap-3', view.steps.length === 5 ? 'sm:grid-cols-5' : 'sm:grid-cols-4')}
        aria-label="Saga steps"
      >
        {view.steps.map((step, index) => {
          const text = textOf(step)
          const attempt = attemptOf(view, step.step)
          const Icon = text.icon
          return (
            <li key={step.step} className="space-y-2.5" aria-label={`${STEP_LABELS[step.step]}: ${text.label}`}>
              <div className={cn('h-2 rounded-full transition-colors duration-500', SEGMENT[step.status])} />
              <div className="space-y-0.5">
                <p className="text-sm font-medium">
                  <span className="mr-1.5 text-muted-foreground tabular-nums">{index + 1}</span>
                  {STEP_LABELS[step.step]}
                </p>
                <p className="font-mono text-[11px] text-muted-foreground">{step.service}</p>
                <p className={cn('flex items-center gap-1 text-xs font-medium [&>svg]:size-3.5', text.className)}>
                  <Icon />
                  {text.label}
                  {attempt > 1 && <span className="font-normal text-muted-foreground">· attempt {attempt}</span>}
                </p>
              </div>
            </li>
          )
        })}
      </ol>
      <div className="space-y-2">
        <div className="flex items-baseline justify-between text-sm">
          <span className="font-medium">{progress.label}</span>
          <span className="text-muted-foreground tabular-nums">{Math.round(progress.percent)}%</span>
        </div>
        <Progress
          value={progress.percent}
          className={cn('h-1.5', BAR_TONE[progress.tone])}
          aria-label={progress.label}
        />
        {view.transfer.reason && <p className="text-sm text-muted-foreground">{view.transfer.reason}</p>}
      </div>
    </div>
  )
}
