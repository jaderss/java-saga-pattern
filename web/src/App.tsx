import { ArrowRight, Play, RotateCcw } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type { Account, LimitUsage, Scenario, TransferSummary } from '@/api/types'
import { ApprovalPanel } from '@/components/saga/approval-panel'
import { MoneyPanel } from '@/components/saga/money-panel'
import { RecentRuns } from '@/components/saga/recent-runs'
import { ApprovalChip, FaultChip, ScenarioList } from '@/components/saga/scenario-list'
import { StatusBadge } from '@/components/saga/status-badge'
import { StepTrack } from '@/components/saga/step-track'
import { ThemeToggle } from '@/components/theme-toggle'
import { Timeline } from '@/components/saga/timeline'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useTransferStream } from '@/hooks/use-transfer-stream'
import { money } from '@/lib/format'
import { awaitingApproval, TERMINAL } from '@/lib/saga'

export default function App() {
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [accounts, setAccounts] = useState<Account[]>([])
  const [limits, setLimits] = useState<LimitUsage[]>([])
  const [recent, setRecent] = useState<TransferSummary[]>([])
  const [active, setActive] = useState<string | null>(null)
  const [before, setBefore] = useState<Account[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const view = useTransferStream(active)

  const refresh = useCallback(async () => {
    const [nextAccounts, nextLimits, nextRecent] = await Promise.all([api.accounts(), api.limits(), api.recent()])
    setAccounts(nextAccounts)
    setLimits(nextLimits)
    setRecent(nextRecent)
  }, [])

  const guard = useCallback(async (work: () => Promise<unknown>) => {
    try {
      await work()
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    void guard(async () => {
      const list = await api.scenarios()
      setScenarios(list)
      setSelected((current) => current ?? list[0]?.id ?? null)
      await refresh()
    })
  }, [guard, refresh])

  const lastEvent = view?.events.at(-1)?.id
  useEffect(() => {
    if (lastEvent !== undefined) void guard(refresh)
  }, [lastEvent, guard, refresh])

  const scenario = useMemo(() => scenarios.find((s) => s.id === selected) ?? null, [scenarios, selected])
  const running = starting || (view !== null && !TERMINAL.has(view.transfer.status))
  const name = (id: string) => accounts.find((account) => account.id === id)?.ownerName ?? id

  const run = () =>
    guard(async () => {
      if (!scenario) return
      setStarting(true)
      try {
        setBefore(await api.accounts())
        const started = await api.start(scenario.request)
        setActive(started.transfer.id)
      } finally {
        setStarting(false)
      }
    })

  const reset = () =>
    guard(async () => {
      await api.reset()
      setActive(null)
      setBefore(null)
      await refresh()
    })

  const open = (id: string) => {
    setBefore(null)
    setActive(id)
  }

  const decide = (approved: boolean) =>
    guard(async () => {
      if (!active) return
      await (approved ? api.approve(active) : api.decline(active))
    })

  const choose = (id: string) => {
    if (id === selected) return
    setSelected(id)
    setActive(null)
    setBefore(null)
    void guard(refresh)
  }

  return (
    <div className="min-h-svh bg-background">
      <header className="border-b">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-5 sm:px-6">
          <div>
            <h1 className="text-lg font-semibold tracking-tight">Saga lab</h1>
            <p className="text-sm text-muted-foreground">
              Bank transfers as an orchestrated saga across transfer, account and risk services.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={reset} disabled={running}>
              <RotateCcw />
              Reset demo data
            </Button>
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="mx-auto grid max-w-6xl gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[320px_1fr]">
        <aside className="space-y-6">
          <section className="space-y-3">
            <h2 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">Scenarios</h2>
            <ScenarioList scenarios={scenarios} selected={selected} onSelect={choose} />
          </section>
          <section className="space-y-2">
            <h2 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">Recent runs</h2>
            <RecentRuns runs={recent} accounts={accounts} active={active} onOpen={open} />
          </section>
        </aside>

        <div className="min-w-0 space-y-6">
          {error && (
            <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {error}
            </p>
          )}

          {scenario && (
            <Card>
              <CardHeader>
                <CardTitle role="heading" aria-level={2}>
                  {scenario.title}
                </CardTitle>
                <CardDescription>{scenario.proves}</CardDescription>
              </CardHeader>
              <CardContent className="flex flex-wrap items-center justify-between gap-4">
                <div className="space-y-2">
                  <p className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="font-medium">{name(scenario.request.sourceAccountId)}</span>
                    <ArrowRight className="size-4 text-muted-foreground" />
                    <span className="font-medium">{name(scenario.request.destinationAccountId)}</span>
                    <span className="text-muted-foreground tabular-nums">
                      {money(scenario.request.amount, scenario.request.currency)}
                    </span>
                  </p>
                  {(scenario.request.faults.length > 0 || scenario.request.requiresApproval) && (
                    <div className="flex flex-wrap gap-1.5">
                      {scenario.request.requiresApproval && <ApprovalChip />}
                      {scenario.request.faults.map((fault) => (
                        <FaultChip key={`${fault.step}-${fault.action}`} fault={fault} />
                      ))}
                    </div>
                  )}
                </div>
                <Button onClick={run} disabled={running}>
                  <Play />
                  {running ? 'Running' : 'Run scenario'}
                </Button>
              </CardContent>
            </Card>
          )}

          {view ? (
            <>
              <Card>
                <CardHeader className="flex flex-row items-center justify-between gap-2">
                  <div className="space-y-1">
                    <CardTitle>Progress</CardTitle>
                    <CardDescription className="font-mono text-[11px]">{view.transfer.id}</CardDescription>
                  </div>
                  <StatusBadge status={view.transfer.status} />
                </CardHeader>
                <CardContent className="space-y-6">
                  <StepTrack view={view} />
                  {awaitingApproval(view) && <ApprovalPanel view={view} name={name} onDecide={decide} />}
                </CardContent>
              </Card>
              <div className="grid gap-6 xl:grid-cols-2">
                <Card>
                  <CardHeader>
                    <CardTitle>Money</CardTitle>
                    <CardDescription>Balances read live from the account service.</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <MoneyPanel view={view} before={before} now={accounts} limits={limits} />
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <CardTitle>Timeline</CardTitle>
                    <CardDescription>Every saga event, as the orchestrator records it.</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <Timeline events={view.events} />
                  </CardContent>
                </Card>
              </div>
            </>
          ) : (
            <div className="rounded-xl border border-dashed px-6 py-16 text-center text-sm text-muted-foreground">
              Run a scenario to watch each step, retry and compensation as it happens.
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
