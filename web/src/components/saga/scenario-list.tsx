import type { Fault, Scenario } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { faultLabel } from '@/lib/format'
import { cn } from '@/lib/utils'
import { StatusBadge } from './status-badge'

const FAULT_HELP = {
  LOSE_REPLY: 'The step commits, then its reply is dropped.',
  CRASH_BEFORE_COMMIT: 'The step runs, then its transaction rolls back before commit.',
}

export function FaultChip({ fault }: { fault: Fault }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="inline-flex rounded border border-dashed px-1.5 py-0.5 font-mono text-[11px] text-timeout">
          {faultLabel(fault)}
        </span>
      </TooltipTrigger>
      <TooltipContent>{FAULT_HELP[fault.kind]}</TooltipContent>
    </Tooltip>
  )
}

export function ApprovalChip() {
  return (
    <span className="inline-flex rounded border border-dashed px-1.5 py-0.5 font-mono text-[11px] text-info">
      Manual approval
    </span>
  )
}

interface Props {
  scenarios: Scenario[]
  selected: string | null
  onSelect: (id: string) => void
}

export function ScenarioList({ scenarios, selected, onSelect }: Props) {
  return (
    <ul className="space-y-1.5" aria-label="Scenarios">
      {scenarios.map((scenario) => (
        <li key={scenario.id}>
          <button
            type="button"
            onClick={() => onSelect(scenario.id)}
            aria-pressed={selected === scenario.id}
            className={cn(
              'w-full rounded-lg border bg-card px-3.5 py-3 text-left transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none',
              selected === scenario.id && 'border-foreground/30 bg-accent',
            )}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-medium">{scenario.title}</span>
              {scenario.request.requiresApproval ? (
                <Badge variant="outline" className="font-medium text-info">
                  You decide
                </Badge>
              ) : (
                <StatusBadge status={scenario.expected} />
              )}
            </div>
            <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{scenario.proves}</p>
          </button>
        </li>
      ))}
    </ul>
  )
}
