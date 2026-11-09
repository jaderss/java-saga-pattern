import type { Account, TransferSummary } from '@/api/types'
import { clock, money } from '@/lib/format'
import { cn } from '@/lib/utils'
import { StatusBadge } from './status-badge'

interface Props {
  runs: TransferSummary[]
  accounts: Account[]
  active: string | null
  onOpen: (id: string) => void
}

export function RecentRuns({ runs, accounts, active, onOpen }: Props) {
  const owner = (id: string) => accounts.find((account) => account.id === id)?.ownerName.split(' ')[0] ?? id
  if (runs.length === 0) return <p className="text-xs text-muted-foreground">No runs yet.</p>
  return (
    <ul className="space-y-1" aria-label="Recent runs">
      {runs.map((run) => (
        <li key={run.id}>
          <button
            type="button"
            onClick={() => onOpen(run.id)}
            className={cn(
              'flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-xs hover:bg-accent',
              active === run.id && 'bg-accent',
            )}
          >
            <span className="min-w-0 truncate">
              {owner(run.terms.sourceAccountId)} → {owner(run.terms.destinationAccountId)}{' '}
              <span className="text-muted-foreground tabular-nums">
                {money(run.terms.amount.amount, run.terms.amount.currency)} · {clock(run.createdAt).slice(0, 8)}
              </span>
            </span>
            <StatusBadge status={run.status} />
          </button>
        </li>
      ))}
    </ul>
  )
}
