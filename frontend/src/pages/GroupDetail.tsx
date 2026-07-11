import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../auth";
import { useUsers, displayName } from "../users";
import {
  Avatar, AvatarStack, Badge, Button, Tabs, categoryIcon, money,
  IconPlus,
} from "../ui";
import type { Expense, Group, GroupBalances, Member, SplitType } from "../types";

const SPLIT_TYPES: SplitType[] = ["EQUAL", "EXACT", "PERCENT", "SHARES"];
type Tab = "expenses" | "balances" | "members";

function dayLabel(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
  const same = (a: Date, b: Date) => a.toDateString() === b.toDateString();
  if (same(d, today)) return "Today";
  if (same(d, yesterday)) return "Yesterday";
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export default function GroupDetail() {
  const { id } = useParams();
  const gid = Number(id);
  const { user } = useAuth();
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>("expenses");
  const [showAdd, setShowAdd] = useState(false);

  const groups = useQuery({ queryKey: ["groups"], queryFn: () => api<Group[]>("GET", "/groups") });
  const groupName = groups.data?.find((g) => g.id === gid)?.name ?? `Group #${gid}`;
  const members = useQuery({ queryKey: ["members", gid], queryFn: () => api<Member[]>("GET", `/groups/${gid}/members`) });
  const expenses = useQuery({ queryKey: ["expenses", gid], queryFn: () => api<Expense[]>("GET", `/groups/${gid}/expenses`) });
  const balances = useQuery({ queryKey: ["gbalances", gid], queryFn: () => api<GroupBalances>("GET", `/balances/group/${gid}`) });
  const refetchAll = () => ["members", "expenses", "gbalances"].forEach((k) => qc.invalidateQueries({ queryKey: [k, gid] }));

  const memberIds = members.data?.map((m) => m.userId) ?? [];
  const { byId } = useUsers([...memberIds, ...(expenses.data?.map((e) => e.paidByUserId) ?? [])]);

  const [newMember, setNewMember] = useState("");
  const addMember = useMutation({
    mutationFn: () => api("POST", `/groups/${gid}/members`, { userId: Number(newMember) }),
    onSuccess: () => { setNewMember(""); refetchAll(); },
    onError: (e) => alert(e instanceof Error ? e.message : "Failed"),
  });

  const settle = useMutation({
    mutationFn: (to: number) => {
      const debt = balances.data!.simplified.find((d) => d.fromUserId === user!.id && d.toUserId === to)!;
      return api("POST", "/settlements", { groupId: gid, fromUserId: user!.id, toUserId: to, amount: debt.amount, currency: "INR" },
        { "Idempotency-Key": crypto.randomUUID() });
    },
    onSuccess: () => { alert("Settlement created — track it in Settlements."); refetchAll(); },
    onError: (e) => alert(e instanceof Error ? e.message : "Failed"),
  });

  const net = balances.data?.net[String(user!.id)] ?? 0;
  const chip = net > 0.005
    ? { text: `You're owed ${money(net)}`, cls: "bg-emerald-400/20 text-emerald-300" }
    : net < -0.005
    ? { text: `You owe ${money(-net)}`, cls: "bg-rose-400/20 text-rose-300" }
    : { text: "Settled up", cls: "bg-white/10 text-slate-300" };

  const groupedExpenses: [string, Expense[]][] = [];
  (expenses.data ?? []).forEach((e) => {
    const label = dayLabel(e.createdAt);
    const bucket = groupedExpenses.find(([l]) => l === label);
    if (bucket) bucket[1].push(e); else groupedExpenses.push([label, [e]]);
  });

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-white/10 bg-white/5 p-4">
        <div className="mb-2 flex items-center justify-between">
          <h1 className="text-xl font-bold text-slate-50">{groupName}</h1>
          <Button onClick={() => setShowAdd((v) => !v)}>
            <span className="flex items-center gap-1.5"><IconPlus className="h-4 w-4" /> Add expense</span>
          </Button>
        </div>
        <div className="flex items-center gap-3">
          <AvatarStack users={memberIds.map((id) => ({ id, name: byId[id]?.displayName }))} />
          <span className="text-xs text-slate-500">{members.data?.length ?? 0} members</span>
          <span className={`rounded-lg px-2.5 py-1 text-xs font-medium ${chip.cls}`}>{chip.text}</span>
        </div>
      </div>

      {showAdd && (
        <AddExpense gid={gid} members={members.data ?? []} meId={user!.id} byId={byId}
          onAdded={() => { refetchAll(); setShowAdd(false); }} />
      )}

      <Tabs<Tab>
        tabs={[{ key: "expenses", label: "Expenses" }, { key: "balances", label: "Balances" }, { key: "members", label: "Members" }]}
        active={tab} onChange={setTab}
      />

      {tab === "expenses" && (
        <div className="rounded-xl border border-white/10 bg-white/5 p-4">
          {!expenses.data?.length && <p className="text-sm text-slate-400">No expenses yet.</p>}
          {groupedExpenses.map(([label, items]) => (
            <div key={label}>
              <p className="mb-1 mt-3 text-xs text-slate-500 first:mt-0">{label}</p>
              <ul className="divide-y divide-white/10">
                {items.map((e) => {
                  const Icon = categoryIcon(e.category);
                  return (
                    <li key={e.id} className="flex items-center gap-3 py-2.5 text-sm">
                      <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-white/10 text-slate-300">
                        <Icon className="h-4 w-4" />
                      </div>
                      <div className="flex-1">
                        <p className="font-medium text-slate-50">{e.description}</p>
                        <p className="text-xs text-slate-500">
                          Paid by {displayName(byId, e.paidByUserId, user!.id)} · {e.splitType.toLowerCase()}
                        </p>
                      </div>
                      <span className="font-semibold text-slate-50">{money(e.amount, e.currency)}</span>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      )}

      {tab === "balances" && (
        <div className="rounded-xl border border-white/10 bg-white/5 p-4">
          {!balances.data?.simplified.length && <p className="text-sm text-slate-400">All settled up 🎉</p>}
          <ul className="divide-y divide-white/10">
            {balances.data?.simplified.map((d, i) => (
              <li key={i} className="flex items-center justify-between py-2.5 text-sm">
                <div className="flex items-center gap-2.5">
                  <Avatar id={d.fromUserId} name={byId[d.fromUserId]?.displayName} size="sm" />
                  <span className="text-slate-200">{displayName(byId, d.fromUserId, user!.id)} → {displayName(byId, d.toUserId, user!.id)}</span>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone="amber">{money(d.amount)}</Badge>
                  {d.fromUserId === user!.id && (
                    <Button onClick={() => settle.mutate(d.toUserId)} disabled={settle.isPending}>Settle</Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {tab === "members" && (
        <div className="rounded-xl border border-white/10 bg-white/5 p-4">
          <ul className="mb-3 divide-y divide-white/10">
            {members.data?.map((m) => (
              <li key={m.userId} className="flex items-center justify-between py-2.5 text-sm">
                <div className="flex items-center gap-2.5">
                  <Avatar id={m.userId} name={byId[m.userId]?.displayName} size="sm" />
                  <span className="text-slate-200">{displayName(byId, m.userId, user!.id)}</span>
                </div>
                <Badge tone={m.role === "OWNER" ? "blue" : "slate"}>{m.role}</Badge>
              </li>
            ))}
          </ul>
          <div className="flex gap-2">
            <input value={newMember} onChange={(e) => setNewMember(e.target.value)} placeholder="Invite by user id"
              className="flex-1 rounded-lg border border-white/10 bg-white/5 px-3 py-1.5 text-sm text-slate-50 outline-none placeholder:text-slate-500 focus:border-indigo-400" />
            <Button variant="ghost" onClick={() => newMember && addMember.mutate()}>Add</Button>
          </div>
        </div>
      )}
    </div>
  );
}

function AddExpense({ gid, members, meId, byId, onAdded }: {
  gid: number; members: Member[]; meId: number; byId: ReturnType<typeof useUsers>["byId"]; onAdded: () => void;
}) {
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("GENERAL");
  const [amount, setAmount] = useState("");
  const [paidBy, setPaidBy] = useState(meId);
  const [splitType, setSplitType] = useState<SplitType>("EQUAL");
  const [selected, setSelected] = useState<Record<number, boolean>>({});
  const [values, setValues] = useState<Record<number, string>>({});

  const chosen = members.filter((m) => selected[m.userId]);
  const input = "rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-slate-50 outline-none placeholder:text-slate-500 focus:border-indigo-400";

  const add = useMutation({
    mutationFn: () => {
      const participants: Record<number, number> = {};
      chosen.forEach((m) => { participants[m.userId] = splitType === "EQUAL" ? 0 : Number(values[m.userId] || 0); });
      return api("POST", `/groups/${gid}/expenses`, {
        description, category, amount: Number(amount), currency: "INR",
        paidByUserId: paidBy, splitType, participants,
      });
    },
    onSuccess: () => { setDescription(""); setAmount(""); setSelected({}); setValues({}); onAdded(); },
    onError: (e) => alert(e instanceof Error ? e.message : "Failed to add expense"),
  });

  return (
    <div className="rounded-xl border border-white/10 bg-white/5 p-4">
      <h2 className="mb-3 font-semibold text-slate-50">Add expense</h2>
      <div className="grid gap-2 sm:grid-cols-2">
        <input className={input} placeholder="Description" value={description} onChange={(e) => setDescription(e.target.value)} />
        <input className={input} placeholder="Category" value={category} onChange={(e) => setCategory(e.target.value)} />
        <input className={input} type="number" placeholder="Amount" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <select className={input} value={paidBy} onChange={(e) => setPaidBy(Number(e.target.value))}>
          {members.map((m) => <option key={m.userId} value={m.userId}>Paid by {displayName(byId, m.userId, meId)}</option>)}
        </select>
        <select className={input} value={splitType} onChange={(e) => setSplitType(e.target.value as SplitType)}>
          {SPLIT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </div>

      <p className="mt-3 mb-1 text-sm text-slate-400">
        Split between{splitType !== "EQUAL" && ` (enter ${splitType.toLowerCase()} per person)`}:
      </p>
      <div className="flex flex-wrap gap-2">
        {members.map((m) => (
          <label key={m.userId} className="flex items-center gap-1 rounded-lg border border-white/10 px-2 py-1 text-sm text-slate-200">
            <input type="checkbox" checked={!!selected[m.userId]}
              onChange={(e) => setSelected({ ...selected, [m.userId]: e.target.checked })} />
            {displayName(byId, m.userId, meId)}
            {splitType !== "EQUAL" && selected[m.userId] && (
              <input className="ml-1 w-16 rounded border border-white/10 bg-white/5 px-1 text-slate-50" type="number"
                value={values[m.userId] || ""} onChange={(e) => setValues({ ...values, [m.userId]: e.target.value })} />
            )}
          </label>
        ))}
      </div>

      <div className="mt-3">
        <Button onClick={() => chosen.length && amount && add.mutate()} disabled={add.isPending}>Add expense</Button>
      </div>
    </div>
  );
}
