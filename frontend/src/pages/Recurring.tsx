import { useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { useAuth } from "../auth";
import { useUsers, displayName } from "../users";
import {
  Badge, Button, SettlementStepper, Tabs, categoryIcon, money, statusTone,
  IconPencil, IconPlus, IconRefresh, IconTrash,
} from "../ui";
import type { Cadence, ObligationStatus, PaymentObligation, RecurringRule, Settlement } from "../types";

type ObTab = "due" | "upcoming" | "settled";
type FormState = "closed" | "create" | RecurringRule;

// A rule's memberUserIds is always exactly [debtorId, creditorId] — recurring
// rules are a 1:1 IOU (like rent between two flatmates), not a group split.
function debtorOf(r: RecurringRule) { return r.memberUserIds[0]; }
function creditorOf(r: RecurringRule) { return r.memberUserIds[1]; }

function cadenceLabel(r: RecurringRule) {
  if (r.cadence === "WEEKLY") return "Weekly";
  if (r.cadence === "MONTHLY") return "Monthly";
  return `Every ${r.intervalDays ?? "?"} days`;
}

export default function Recurring() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [formState, setFormState] = useState<FormState>("closed");
  const [tab, setTab] = useState<ObTab>("due");

  const rules = useQuery({ queryKey: ["recurring"], queryFn: () => api<RecurringRule[]>("GET", "/recurring") });
  const obligations = useQuery({ queryKey: ["obligations"], queryFn: () => api<PaymentObligation[]>("GET", "/obligations") });

  const ruleById: Record<number, RecurringRule> = {};
  rules.data?.forEach((r) => { ruleById[r.id] = r; });

  const allMemberIds = rules.data?.flatMap((r) => r.memberUserIds) ?? [];
  const { byId } = useUsers(allMemberIds);

  // Obligations that already have a linked Settlement get their live claim/confirm/
  // dispute progress fetched inline, instead of a dead "settled" badge.
  const settlementRefs = Array.from(new Set(
    (obligations.data ?? []).map((o) => o.settlementId).filter((id): id is number => id != null),
  ));
  const settlementResults = useQueries({
    queries: settlementRefs.map((id) => ({
      queryKey: ["settlement", id], queryFn: () => api<Settlement>("GET", `/settlements/${id}`), staleTime: 30 * 1000,
    })),
  });
  const settlementById: Record<number, Settlement> = {};
  settlementResults.forEach((r) => { if (r.data) settlementById[r.data.id] = r.data; });

  const refetchObligations = () => qc.invalidateQueries({ queryKey: ["obligations"] });
  const closeForm = () => { setFormState("closed"); qc.invalidateQueries({ queryKey: ["recurring"] }); refetchObligations(); };

  // Settling a recurring bill works exactly like settling an expense: it creates
  // a real Settlement (PENDING) and links it to the obligation, then the payer
  // claims it and the receiver confirms/disputes — same handshake, shown inline
  // right here instead of sending people off to a different page.
  const settle = useMutation({
    mutationFn: async (o: PaymentObligation) => {
      const r = ruleById[o.recurringRuleId];
      const created = await api<Settlement>("POST", "/settlements", {
        groupId: null, fromUserId: debtorOf(r), toUserId: creditorOf(r),
        amount: o.amount, currency: o.currency,
      }, { "Idempotency-Key": crypto.randomUUID() });
      await api("POST", `/obligations/${o.id}/settle`, { settlementId: created.id });
    },
    onSuccess: () => { refetchObligations(); qc.invalidateQueries({ queryKey: ["settlements"] }); },
    onError: (e) => alert(e instanceof Error ? e.message : "Failed to settle"),
  });

  const act = useMutation({
    mutationFn: ({ id, action, body }: { id: number; action: string; body?: unknown }) =>
      api("POST", `/settlements/${id}/${action}`, body),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["settlements"] }); qc.invalidateQueries({ queryKey: ["settlement"] }); },
    onError: (e) => alert(e instanceof Error ? e.message : "Action failed"),
  });

  // Rules only turn into actual due bills when the scheduler tick runs (daily at
  // 6am in production). If you just created a rule, nothing's generated yet — this
  // triggers that same tick on demand instead of waiting for tomorrow's cron.
  const runTick = useMutation({
    mutationFn: () => api("POST", "/recurring/run"),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); refetchObligations(); },
    onError: (e) => alert(e instanceof Error ? e.message : "Failed to check for due bills"),
  });

  const del = useMutation({
    mutationFn: (id: number) => api("DELETE", `/recurring/${id}`),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); refetchObligations(); },
    onError: (e) => alert(e instanceof Error ? e.message : "Failed to delete rule"),
  });

  function direction(r: RecurringRule) {
    const meId = user!.id;
    const otherId = debtorOf(r) === meId ? creditorOf(r) : debtorOf(r);
    const iOwe = debtorOf(r) === meId;
    return { otherId, iOwe, label: iOwe ? `You owe ${displayName(byId, otherId)}` : `${displayName(byId, otherId)} owes you` };
  }

  const filtered = (obligations.data ?? []).filter((o) => {
    if (tab === "due") return o.status === "DUE" || o.status === "OVERDUE";
    if (tab === "upcoming") return o.status === "UPCOMING";
    return o.status === "SETTLED";
  });

  const dueCount = (obligations.data ?? []).filter((o) => o.status === "DUE" || o.status === "OVERDUE").length;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-slate-50">Recurring</h1>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => runTick.mutate()} disabled={runTick.isPending}>
            <span className="flex items-center gap-1.5"><IconRefresh className="h-4 w-4" /> Check for due bills</span>
          </Button>
          <Button onClick={() => setFormState((s) => (s === "create" ? "closed" : "create"))}>
            <span className="flex items-center gap-1.5"><IconPlus className="h-4 w-4" /> New rule</span>
          </Button>
        </div>
      </div>
      <p className="-mt-4 text-xs text-slate-500">
        New bills normally appear automatically once a day. "Check for due bills" runs that check right now instead of waiting.
      </p>

      {formState !== "closed" && (
        <RuleForm
          key={formState === "create" ? "create" : formState.id}
          meId={user!.id}
          editing={formState === "create" ? null : formState}
          onDone={closeForm}
          onCancel={() => setFormState("closed")}
        />
      )}

      <div className="rounded-xl border border-white/10 bg-white/5 p-4">
        <h2 className="mb-3 font-semibold text-slate-50">Your rules</h2>
        {!rules.data?.length && <p className="text-sm text-slate-400">No recurring rules yet — rent, subscriptions, anything that repeats between you and someone else.</p>}
        <ul className="divide-y divide-white/10">
          {rules.data?.map((r) => {
            const Icon = categoryIcon(r.category);
            const d = direction(r);
            const mine = r.createdBy === user!.id;
            return (
              <li key={r.id} className="flex items-center gap-3 py-2.5 text-sm">
                <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-white/10 text-slate-300">
                  <Icon className="h-4 w-4" />
                </div>
                <div className="flex-1">
                  <p className="font-medium text-slate-50">{r.description}</p>
                  <p className="text-xs text-slate-500">
                    {d.label} · {cadenceLabel(r)} · next due {new Date(r.nextDueDate).toLocaleDateString()}
                  </p>
                </div>
                <span className={`font-semibold ${d.iOwe ? "text-rose-400" : "text-emerald-400"}`}>{money(r.amount, r.currency)}</span>
                {mine && (
                  <div className="flex items-center gap-1">
                    <button type="button" onClick={() => setFormState(r)} aria-label="Edit rule"
                      className="rounded-lg p-1.5 text-slate-500 hover:bg-white/10 hover:text-slate-200">
                      <IconPencil className="h-4 w-4" />
                    </button>
                    <button type="button" aria-label="Delete rule"
                      onClick={() => { if (confirm(`Delete "${r.description}"? This won't affect bills already settled.`)) del.mutate(r.id); }}
                      className="rounded-lg p-1.5 text-slate-500 hover:bg-rose-400/20 hover:text-rose-300">
                      <IconTrash className="h-4 w-4" />
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      </div>

      <div className="space-y-3">
        <Tabs<ObTab>
          tabs={[
            { key: "due", label: `Due${dueCount ? ` (${dueCount})` : ""}` },
            { key: "upcoming", label: "Upcoming" },
            { key: "settled", label: "Settled" },
          ]}
          active={tab} onChange={setTab}
        />
        <div className="rounded-xl border border-white/10 bg-white/5 p-4">
          {!filtered.length && <p className="text-sm text-slate-400">Nothing here.</p>}
          <ul className="divide-y divide-white/10">
            {filtered.map((o) => {
              const r = ruleById[o.recurringRuleId];
              const d = r ? direction(r) : null;
              const settlement = o.settlementId ? settlementById[o.settlementId] : undefined;
              return (
                <li key={o.id} className="py-2.5">
                  <div className="flex items-center justify-between text-sm">
                    <div>
                      <p className="font-medium text-slate-50">{o.description}</p>
                      <p className="text-xs text-slate-500">
                        Due {new Date(o.dueDate).toLocaleDateString()}{d && ` · ${d.label}`}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-slate-50">{money(o.amount, o.currency)}</span>
                      {!settlement && <Badge tone={statusTone[o.status as ObligationStatus]}>{o.status.toLowerCase()}</Badge>}
                      {!settlement && o.status !== "SETTLED" && d?.iOwe && (
                        <Button onClick={() => settle.mutate(o)} disabled={settle.isPending}>Settle up</Button>
                      )}
                    </div>
                  </div>

                  {settlement && (
                    <div className="mt-2.5 rounded-lg border border-white/10 bg-white/5 p-3">
                      <div className="mb-2">
                        <SettlementStepper status={settlement.status} />
                      </div>
                      <div className="flex items-center justify-between">
                        <p className="text-xs text-slate-500">
                          {settlement.utr && `UTR ${settlement.utr} · `}
                          {settlement.disputeReason && `disputed: ${settlement.disputeReason}`}
                        </p>
                        <div className="flex gap-2">
                          {settlement.fromUserId === user!.id && (settlement.status === "PENDING" || settlement.status === "DISPUTED") && (
                            <Button onClick={() => act.mutate({ id: settlement.id, action: "claim", body: { utr: prompt("UPI ref / UTR (optional)") || null } })}>
                              I've paid
                            </Button>
                          )}
                          {settlement.toUserId === user!.id && settlement.status === "PAYMENT_CLAIMED" && (
                            <>
                              <Button variant="danger"
                                onClick={() => { const reason = prompt("Why are you disputing?"); if (reason) act.mutate({ id: settlement.id, action: "dispute", body: { reason } }); }}>
                                Dispute
                              </Button>
                              <Button variant="primary" onClick={() => act.mutate({ id: settlement.id, action: "acknowledge" })}>
                                Confirm receipt
                              </Button>
                            </>
                          )}
                        </div>
                      </div>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    </div>
  );
}

const CADENCES: Cadence[] = ["WEEKLY", "MONTHLY", "CUSTOM"];

function RuleForm({ meId, editing, onDone, onCancel }: {
  meId: number; editing: RecurringRule | null; onDone: () => void; onCancel: () => void;
}) {
  const isEdit = !!editing;
  const editDebtor = editing ? debtorOf(editing) : null;
  const editCreditor = editing ? creditorOf(editing) : null;
  const editOtherId = editing ? (editDebtor === meId ? editCreditor : editDebtor) : null;
  const editDirection: "owed_to_me" | "i_owe" = editing && editDebtor === meId ? "i_owe" : "owed_to_me";

  const [description, setDescription] = useState(editing?.description ?? "");
  const [category, setCategory] = useState(editing?.category ?? "RENT");
  const [amount, setAmount] = useState(editing ? String(editing.amount) : "");
  const [otherUserId, setOtherUserId] = useState(editOtherId != null ? String(editOtherId) : "");
  const [direction, setDirection] = useState<"owed_to_me" | "i_owe">(editDirection);
  const [cadence, setCadence] = useState<Cadence>(editing?.cadence ?? "MONTHLY");
  const [intervalDays, setIntervalDays] = useState(editing?.intervalDays ? String(editing.intervalDays) : "30");
  const [dueDate, setDueDate] = useState(editing?.nextDueDate ?? new Date().toISOString().slice(0, 10));

  const input = "rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-slate-50 outline-none placeholder:text-slate-500 focus:border-indigo-400";

  const otherId = Number(otherUserId);
  const memberUserIds = direction === "owed_to_me" ? [otherId, meId] : [meId, otherId];

  const save = useMutation({
    mutationFn: () => {
      const body = {
        description, category, amount: Number(amount), currency: "INR",
        cadence, intervalDays: cadence === "CUSTOM" ? Number(intervalDays) : null,
        memberUserIds,
      };
      return isEdit
        ? api("PATCH", `/recurring/${editing!.id}`, { ...body, nextDueDate: dueDate })
        : api("POST", "/recurring", { ...body, groupId: null, firstDueDate: dueDate });
    },
    onSuccess: onDone,
    onError: (e) => alert(e instanceof Error ? e.message : `Failed to ${isEdit ? "update" : "create"} rule`),
  });

  const valid = description.trim() && Number(amount) > 0 && dueDate
    && Number.isInteger(otherId) && otherId > 0
    && (cadence !== "CUSTOM" || Number(intervalDays) > 0);

  return (
    <div className="rounded-xl border border-white/10 bg-white/5 p-4">
      <h2 className="mb-3 font-semibold text-slate-50">{isEdit ? "Edit recurring rule" : "New recurring rule"}</h2>
      <div className="grid gap-2 sm:grid-cols-2">
        <input className={input} placeholder="Description (Rent, Wi-Fi…)" value={description} onChange={(e) => setDescription(e.target.value)} />
        <input className={input} placeholder="Category" value={category} onChange={(e) => setCategory(e.target.value)} />
        <input className={input} type="number" placeholder="Amount" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <input className={input} type="number" placeholder="Other person's user id" value={otherUserId} onChange={(e) => setOtherUserId(e.target.value)} />
        <select className={input} value={direction} onChange={(e) => setDirection(e.target.value as typeof direction)}>
          <option value="owed_to_me">They owe me</option>
          <option value="i_owe">I owe them</option>
        </select>
        <select className={input} value={cadence} onChange={(e) => setCadence(e.target.value as Cadence)}>
          {CADENCES.map((c) => <option key={c} value={c}>{c === "CUSTOM" ? "Custom interval" : c[0] + c.slice(1).toLowerCase()}</option>)}
        </select>
        {cadence === "CUSTOM" && (
          <input className={input} type="number" placeholder="Every N days" value={intervalDays} onChange={(e) => setIntervalDays(e.target.value)} />
        )}
        <label className="flex flex-col gap-1 text-xs text-slate-400 sm:col-span-2">
          {isEdit ? "Next due date" : "First due date"}
          <input className={input} type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
        </label>
      </div>

      <div className="mt-3 flex gap-2">
        <Button onClick={() => valid && save.mutate()} disabled={!valid || save.isPending}>
          {isEdit ? "Save changes" : "Create rule"}
        </Button>
        <Button variant="ghost" onClick={onCancel}>Cancel</Button>
      </div>
    </div>
  );
}
