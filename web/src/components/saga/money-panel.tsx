import type { Account, LimitUsage, TransferView } from '@/api/types'
import { money, signedMoney } from '@/lib/format'
import { TERMINAL } from '@/lib/saga'
import { cn } from '@/lib/utils'

interface Props {
  view: TransferView
  before: Account[] | null
  now: Account[]
  limits: LimitUsage[]
}

export function MoneyPanel({ view, before, now, limits }: Props) {
  const { terms, status } = view.transfer
  const currency = terms.amount.currency
  const rows = [terms.sourceAccountId, terms.destinationAccountId].map((id) => {
    const current = now.find((account) => account.id === id)
    const start = before?.find((account) => account.id === id)
    const change = current && start ? round(current.balance - start.balance) : null
    return { id, current, start, change }
  })
  const usage = limits.find((limit) => limit.accountId === terms.sourceAccountId)
  const unchanged = rows.every((row) => row.change === 0)

  return (
    <div className="space-y-4">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-muted-foreground">
            <th className="pb-2 font-normal">Account</th>
            <th className="pb-2 text-right font-normal">Before</th>
            <th className="pb-2 text-right font-normal">Now</th>
            <th className="pb-2 text-right font-normal">Change</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {rows.map(({ id, current, start, change }) => (
            <tr key={id}>
              <td className="py-2.5">
                <p className="font-medium">{current?.ownerName ?? id}</p>
                <p className="font-mono text-[11px] text-muted-foreground">
                  {id}
                  {current?.status === 'FROZEN' && <span className="ml-1.5 text-destructive">frozen</span>}
                </p>
              </td>
              <td className="py-2.5 text-right text-muted-foreground tabular-nums">
                {start ? money(start.balance, currency) : '-'}
              </td>
              <td className="py-2.5 text-right font-medium tabular-nums">
                {current ? money(current.balance, currency) : '-'}
              </td>
              <td
                className={cn(
                  'py-2.5 text-right tabular-nums',
                  change === null || change === 0 ? 'text-muted-foreground' : change > 0 ? 'text-success' : 'text-destructive',
                )}
              >
                {change === null ? '-' : signedMoney(change, currency)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {before && TERMINAL.has(status) && (
        <p className={cn('rounded-md px-3 py-2 text-sm', verdictTone(status, unchanged))}>
          {verdict(status, unchanged, money(terms.amount.amount, currency))}
        </p>
      )}
      {usage && (
        <p className="text-xs text-muted-foreground">
          Daily limit of {terms.sourceAccountId}: {money(usage.reserved, currency)} reserved, {money(usage.used, currency)}{' '}
          used of {money(usage.dailyLimit, currency)}
        </p>
      )}
    </div>
  )
}

function round(value: number) {
  return Math.round(value * 100) / 100
}

function verdict(status: TransferView['transfer']['status'], unchanged: boolean, amount: string) {
  if (status === 'COMPLETED') return `${amount} moved once, from source to destination.`
  return unchanged
    ? 'Both balances are back where they started.'
    : 'Balances changed by other activity while this saga ran.'
}

function verdictTone(status: TransferView['transfer']['status'], unchanged: boolean) {
  if (status === 'COMPLETED') return 'bg-success/10 text-success'
  return unchanged ? 'bg-warning/10 text-foreground' : 'bg-destructive/10 text-destructive'
}
