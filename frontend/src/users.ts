import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import type { User } from "./types";

/**
 * Resolves a batch of user ids to display names/emails via the auth-service
 * batch lookup (GET /auth/internal/users?ids=...). That endpoint requires a
 * valid bearer token but isn't otherwise restricted, so the SPA can call it
 * directly instead of showing "User #123" everywhere.
 */
export function useUsers(ids: (number | undefined | null)[]) {
  const uniq = Array.from(new Set(ids.filter((v): v is number => v != null))).sort((a, b) => a - b);
  const key = uniq.join(",");

  const q = useQuery({
    queryKey: ["users", key],
    queryFn: () => api<User[]>("GET", `/auth/internal/users?ids=${uniq.join(",")}`),
    enabled: uniq.length > 0,
    staleTime: 5 * 60 * 1000,
  });

  const byId: Record<number, User> = {};
  q.data?.forEach((u) => { byId[u.id] = u; });
  return { byId, isLoading: q.isLoading };
}

export function displayName(byId: Record<number, User>, id: number, meId?: number) {
  if (id === meId) return "You";
  return byId[id]?.displayName ?? `User #${id}`;
}
