import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { useAuth } from "../auth";
import { useUsers, displayName } from "../users";
import {
  Avatar, Button, money,
  IconArrowDownLeft, IconArrowUpRight, notificationVisual,
} from "../ui";
import type { Expense, Notification, Settlement, UserBalances } from "../types";

export default function Dashboard() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const balances = useQuery({ queryKey: ["balances", "me"], queryFn: () => api<UserBalances>("GET", "/balances/me") });
  const notes = useQuery({ queryKey: ["notifications"], queryFn: () => api<Notification[]>("GET", "/notifications") });

  const expenseRefs = Array.from(new Set(
    notes.data?.filter((n) => n.refType === "EXPENSE" && n.refId != null).map((n) => n.refId!) ?? [],
  ));
  const settlementRefs = Array.from(new Set(
    notes.data?.filter((n) => n.refType === "SETTLEMENT" && n.refId != null).map((n) => n.refId!) ?? [],
  ));
  const expenseResults = useQueries({
    queries: expenseRefs.map((id) => ({
      queryKey: ["expense", id], queryFn: () => api<Expense>("GET", `/expenses/${id}`), staleTime: 5 * 60 * 1000,
    })),
  });
  const settlementResults = useQueries({
    queries: settlementRefs.map((id) => ({
      queryKey: ["settlement", id], queryFn: () => api<Settlement>("GET", `/settlements/${id}`), staleTime: 5 * 60 * 1000,
    })),
  });
  const expenseById: Record<number, Expense> = {};
  expenseResults.forEach((r) => { if (r.data) expenseById[r.data.id] = r.data; });
  const settlementById: Record<number, Settlement> = {};
  settlementResults.forEach((r) => { if (r.data) settlementById[r.data.id] = r.data; });

  const otherIds = [
    ...(balances.data?.breakdown.map((d) => (d.fromUserId === user!.id ? d.toUserId : d.fromUserId)) ?? []),
    ...Object.values(expenseById).map((e) => e.paidByUserId),
    ...Object.values(settlementById).flatMap((s) => [s.fromUserId, s.toUserId]),
  ];
  const { byId } = useUsers(otherIds);

  function describe(n: Notification): string {
    const who = (id: number) => displayName(byId, id, user!.id);
    if (n.refType === "EXPENSE" && n.refId != null) {
      const e = expenseById[n.refId];
      if (e) return `${who(e.paidByUserId)} added "${e.description}" · ${money(e.amount, e.currency)}`;
    }
    if (n.refType === "SETTLEMENT" && n.refId != null) {
      const s = settlementById[n.refId];
      if (s) {
        if (n.type === "PAYMENT_CLAIMED") return `${who(s.fromUserId)} says they paid you ${money(s.amount, s.currency)} — confirm receipt`;
        if (n.type === "PAYMENT_ACKNOWLEDGED") return `${who(s.toUserId)} confirmed your ${money(s.amount, s.currency)} payment`;
        if (n.type === "PAYMENT_DISPUTED") return `${who(s.toUserId)} disputed your payment: ${s.disputeReason ?? "no reason given"}`;
      }
    }
    return n.message;
  }

  const settle = useMutation({
    mutationFn: (toUserId: number) => {
      const debt = balances.data!.breakdown.find((d) => d.fromUserId === user!.id && d.toUserId === toUserId)!;
      return api("POST", "/settlements", { groupId: null, fromUserId: user!.id, toUserId, amount: debt.amount, currency: "INR" },
        { "Idempotency-Key": crypto.randomUUID() });
    },
    onSuccess: () => { alert("Settlement created — track it in Settlements."); qc.invalidateQueries({ queryKey: ["balances", "me"] }); },
    onError: (e) => alert(e instanceof Error ? e.message : "Failed"),
  });

  const net = (balances.data?.totalReceivable ?? 0) - (balances.data?.totalOwed ?? 0);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-slate-50">Dashboard</h1>

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="rounded-xl bg-emerald-400/10 p-4">
          <p className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-emerald-300">
            <IconArrowDownLeft className="h-3.5 w-3.5" /> You are owed
          </p>
          <p className="text-2xl font-bold text-emerald-200">{money(balances.data?.totalReceivable ?? 0)}</p>
        </div>
        <div className="rounded-xl bg-rose-400/10 p-4">
          <p className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-rose-300">
            <IconArrowUpRight className="h-3.5 w-3.5" /> You owe
          </p>
          <p className="text-2xl font-bold text-rose-200">{money(balances.data?.totalOwed ?? 0)}</p>
        </div>
        <div className="rounded-xl bg-white/5 p-4">
          <p className="mb-1.5 text-sm font-medium text-slate-300">Net position</p>
          <p className="text-2xl font-bold text-slate-50">{net >= 0 ? "+" : ""}{money(net)}</p>
        </div>
      </div>

      <div className="rounded-xl border border-white/10 bg-white/5 p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-semibold text-slate-50">Who owes whom</h2>
          {!!balances.data?.breakdown.length && <span className="text-xs text-slate-500">{balances.data.breakdown.length} open</span>}
        </div>
        {!balances.data?.breakdown.length && <p className="text-sm text-slate-400">All settled up 🎉</p>}
        <ul className="divide-y divide-white/10">
          {balances.data?.breakdown.map((d, i) => {
            const iOwe = d.fromUserId === user!.id;
            const otherId = iOwe ? d.toUserId : d.fromUserId;
            const other = byId[otherId];
            return (
              <li key={i} className="flex items-center justify-between py-2.5 text-sm">
                <div className="flex items-center gap-2.5">
                  <Avatar id={otherId} name={other?.displayName} size="sm" />
                  <span className="text-slate-200">{iOwe ? `You owe ${displayName(byId, otherId)}` : `${displayName(byId, otherId)} owes you`}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className={`font-semibold ${iOwe ? "text-rose-400" : "text-emerald-400"}`}>{money(d.amount)}</span>
                  {iOwe && (
                    <Button onClick={() => settle.mutate(otherId)} disabled={settle.isPending}>Settle up</Button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      </div>

      <div className="rounded-xl border border-white/10 bg-white/5 p-4">
        <h2 className="mb-3 font-semibold text-slate-50">Recent activity</h2>
        {!notes.data?.length && <p className="text-sm text-slate-400">Nothing yet.</p>}
        <ul className="divide-y divide-white/10">
          {notes.data?.slice(0, 12).map((n) => {
            const { Icon, tone } = notificationVisual(n.type);
            return (
              <li key={n.id} className="flex items-start gap-3 py-2.5 text-sm">
                <div className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full ${tone}`}>
                  <Icon className="h-3.5 w-3.5" />
                </div>
                <div className="flex-1">
                  <p className={n.read ? "text-slate-400" : "font-medium text-slate-50"}>{describe(n)}</p>
                  <p className="mt-0.5 text-xs text-slate-500">{new Date(n.createdAt).toLocaleString()}</p>
                </div>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
