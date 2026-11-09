import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { TransferView } from '@/api/types'
import { newer, TERMINAL } from '@/lib/saga'

export function useTransferStream(id: string | null) {
  const [view, setView] = useState<TransferView | null>(null)

  useEffect(() => {
    if (!id) return
    const source = new EventSource(api.streamUrl(id))
    source.addEventListener('saga', (message) => {
      const next = JSON.parse((message as MessageEvent<string>).data) as TransferView
      setView((current) => newer(current, next))
      if (TERMINAL.has(next.transfer.status)) source.close()
    })
    return () => source.close()
  }, [id])

  return id && view?.transfer.id === id ? view : null
}
