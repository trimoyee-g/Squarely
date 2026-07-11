import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../auth";
import { useUsers } from "../users";
import { AvatarStack, Button, IconChevronRight, IconPlus, money } from "../ui";
import type { Group, GroupBalances, Member } from "../types";

export default function Groups() {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const groups = useQuery({ queryKey: ["groups"], queryFn: () => api<Group[]>("GET", "/groups") });
  const create = useMutation({
    mutationFn: () => api<Group>("POST", "/groups", { name }),
    onSuccess: () => { setName(""); qc.invalidateQueries({ queryKey: ["groups"] }); },
  });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-slate-50">Groups</h1>

      <div className="flex gap-2">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Flatmates, Goa Trip…"
          className="flex-1 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-slate-50 outline-none placeholder:text-slate-500 focus:border-indigo-400" />
        <Button onClick={() => name.trim() && create.mutate()} disabled={create.isPending}>
          <span className="flex items-center gap-1.5"><IconPlus className="h-4 w-4" /> Create group</span>
        </Button>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        {groups.data?.map((g) => <GroupCard key={g.id} group={g} />)}
        {!groups.data?.length && <p className="text-sm text-slate-400">No groups yet — create one above.</p>}
      </div>
    </div>
  );
}

function GroupCard({ group }: { group: Group }) {
  const { user } = useAuth();
  const members = useQuery({ queryKey: ["members", group.id], queryFn: () => api<Member[]>("GET", `/groups/${group.id}/members`) });
  const balances = useQuery({ queryKey: ["gbalances", group.id], queryFn: () => api<GroupBalances>("GET", `/balances/group/${group.id}`) });
  const { byId } = useUsers(members.data?.map((m) => m.userId) ?? []);

  const net = balances.data?.net[String(user!.id)] ?? 0;
  const chip = net > 0.005
    ? { text: `You're owed ${money(net)}`, cls: "bg-emerald-400/20 text-emerald-300" }
    : net < -0.005
    ? { text: `You owe ${money(-net)}`, cls: "bg-rose-400/20 text-rose-300" }
    : { text: "Settled up", cls: "bg-white/10 text-slate-300" };

  return (
    <Link to={`/groups/${group.id}`}>
      <div className="rounded-xl border border-white/10 bg-white/5 p-4 transition hover:border-indigo-400/50">
        <div className="mb-3 flex items-center justify-between">
          <span className="font-semibold text-slate-50">{group.name}</span>
          <IconChevronRight className="h-4 w-4 text-slate-600" />
        </div>
        <div className="mb-3 flex items-center justify-between">
          <AvatarStack users={(members.data ?? []).map((m) => ({ id: m.userId, name: byId[m.userId]?.displayName }))} />
          <span className="text-xs text-slate-500">{group.memberCount} members</span>
        </div>
        <span className={`inline-block rounded-lg px-2.5 py-1 text-xs font-medium ${chip.cls}`}>{chip.text}</span>
      </div>
    </Link>
  );
}
