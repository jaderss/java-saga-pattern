import { Check, X } from 'lucide-react'
import { useState } from 'react'
import type { TransferView } from '@/api/types'
import { Button } from '@/components/ui/button'
import { money } from '@/lib/format'

interface Props {
  view: TransferView
  name: (accountId: string) => string
  onDecide: (approved: boolean) => Promise<void>
}

export function ApprovalPanel({ view, name, onDecide }: Props) {
  const [pending, setPending] = useState(false)
  const { terms } = view.transfer
  const decide = async (approved: boolean) => {
    setPending(true)
    try {
      await onDecide(approved)
    } finally {
      setPending(false)
    }
  }
  return (
    <section
      aria-label="Approval"
      className="flex flex-wrap items-center justify-between gap-4 rounded-lg border border-info/40 bg-info/10 px-4 py-3"
    >
      <div className="space-y-0.5 text-sm">
        <p className="font-medium">Your approval is needed</p>
        <p className="text-muted-foreground">
          {money(terms.amount.amount, terms.amount.currency)} is debited from {name(terms.sourceAccountId)} and held.
          Approve to credit {name(terms.destinationAccountId)}, or decline to refund it.
        </p>
      </div>
      <div className="flex gap-2">
        <Button variant="outline" onClick={() => decide(false)} disabled={pending}>
          <X />
          Decline
        </Button>
        <Button onClick={() => decide(true)} disabled={pending}>
          <Check />
          Approve
        </Button>
      </div>
    </section>
  )
}
