import { Badge } from '@/components/ui/badge'
import type { SagaStatus } from '@/api/types'
import { STATUS_LABELS, STATUS_TONE, TONE_BG, TONE_TEXT } from '@/lib/format'
import { cn } from '@/lib/utils'

export function StatusBadge({ status, className }: { status: SagaStatus; className?: string }) {
  const tone = STATUS_TONE[status]
  return (
    <Badge variant="outline" className={cn('gap-1.5 font-medium', TONE_TEXT[tone], className)}>
      <span
        className={cn(
          'size-1.5 rounded-full',
          TONE_BG[tone],
          (status === 'RUNNING' || status === 'COMPENSATING') && 'animate-pulse',
        )}
      />
      {STATUS_LABELS[status]}
    </Badge>
  )
}
