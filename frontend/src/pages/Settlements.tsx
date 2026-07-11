import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { useAuth } from "../auth";
import { useUsers, displayName } from "../users";
import { Avatar, Button, IconArrowUpRight, SettlementStepper, money } from "../ui";
import type { Settlement } from "../types";

export default function Settlements() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["settlements"], queryFn: () => api<Settlement[]>("GET", "/settlements") });
  const { byId } = useUsers((q.data ?? []).flatMap((s) => [s.fromUserId, s.toUserId]));

  const act = useMutation({
    mutationFn: ({ id, action, body }: { id: number; action: string; body?: unknown }) =>
      api("POST", `/settlements/${id}/${action}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["settlements"] }),
    onError: (e) => alert(e instanceof Error ? e.message : "Action failed"),
  });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-slate-50">Settlements</h1>
      {!q.data?.length && <p className="text-sm text-slate-400">No settlements yet.</p>}
      <div className="space-y-3">
        {q.data?.map((s) => {
          const iAmPayer = s.fromUserId === user!.id;
          const iAmReceiver = s.toUserId === user!.id;
          const settled = s.status === "SETTLED";
          return (
            <div key={s.id} className={`rounded-xl border border-white/10 bg-white/5 p-4 ${settled ? "opacity-70" : ""}`}>
              <div className="mb-3 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Avatar id={s.fromUserId} name={byId[s.fromUserId]?.displayName} size="sm" />
                  <IconArrowUpRight className="h-3.5 w-3.5 rotate-45 text-slate-600" />
                  <Avatar id={s.toUserId} name={byId[s.toUserId]?.displayName} size="sm" />
                  <span className="ml-1 text-sm text-slate-200">
                    {displayName(byId, s.fromUserId, user!.id)} <span className="text-slate-500">→</span> {displayName(byId, s.toUserId, user!.id)}
                  </span>
                </div>
                <span className="font-semibold text-slate-50">{money(s.amount, s.currency)}</span>
              </div>

              <div className="mb-3">
                <SettlementStepper status={s.status} />
              </div>

              <div className="flex items-center justify-between">
                <p className="text-xs text-slate-500">
                  {s.utr && `UTR ${s.utr} · `}
                  {s.groupId ? `group #${s.groupId}` : "personal"}
                  {s.disputeReason && ` · disputed: ${s.disputeReason}`}
                </p>
                <div className="flex gap-2">
                  {iAmPayer && (s.status === "PENDING" || s.status === "DISPUTED") && (
                    <Button onClick={() => act.mutate({ id: s.id, action: "claim", body: { utr: prompt("UPI ref / UTR (optional)") || null } })}>
                      I've paid
                    </Button>
                  )}
                  {iAmReceiver && s.status === "PAYMENT_CLAIMED" && (
                    <>
                      <Button variant="danger"
                        onClick={() => { const r = prompt("Why are you disputing?"); if (r) act.mutate({ id: s.id, action: "dispute", body: { reason: r } }); }}>
                        Dispute
                      </Button>
                      <Button variant="primary" onClick={() => act.mutate({ id: s.id, action: "acknowledge" })}>
                        Confirm receipt
                      </Button>
                    </>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
