import { useEffect, useRef } from 'react'
import type { EventView } from '@/api/types'
import { clock, describe, TONE_BG, TONE_TEXT } from '@/lib/format'
import { cn } from '@/lib/utils'

export function Timeline({ events }: { events: EventView[] }) {
  const list = useRef<HTMLOListElement>(null)

  useEffect(() => {
    list.current?.scrollTo({ top: list.current.scrollHeight, behavior: 'smooth' })
  }, [events.length])

  return (
    <ol ref={list} aria-live="polite" className="max-h-96 space-y-0 overflow-y-auto pr-1">
      {events.map((event) => {
        const { title, tone } = describe(event)
        return (
          <li key={event.id} className="group relative flex gap-3 pb-3 pl-1 last:pb-0">
            <span className="absolute top-3 bottom-0 left-[7px] w-px bg-border group-last:hidden" aria-hidden />
            <span className={cn('relative mt-1.5 size-2 shrink-0 rounded-full ring-4 ring-card', TONE_BG[tone])} />
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline justify-between gap-3">
                <p className={cn('text-sm font-medium', tone !== 'neutral' && TONE_TEXT[tone])}>{title}</p>
                <time className="shrink-0 font-mono text-[11px] text-muted-foreground tabular-nums">{clock(event.at)}</time>
              </div>
              {event.detail && <p className="text-xs text-muted-foreground">{event.detail}</p>}
            </div>
          </li>
        )
      })}
    </ol>
  )
}
