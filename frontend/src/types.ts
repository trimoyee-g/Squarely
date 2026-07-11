export type SplitType = "EQUAL" | "EXACT" | "PERCENT" | "SHARES";
export type SettlementStatus = "PENDING" | "PAYMENT_CLAIMED" | "SETTLED" | "DISPUTED";

export interface User { id: number; email: string; displayName: string; }
export interface Group { id: number; name: string; createdBy: number; memberCount: number; }
export interface Member { userId: number; role: "OWNER" | "MEMBER"; joinedAt: string; }

export interface Expense {
  id: number; groupId: number; description: string; category: string;
  amount: number; currency: string; paidByUserId: number; splitType: SplitType;
  createdBy: number; createdAt: string; splits: Record<string, number>;
}

export interface PairDebt { fromUserId: number; toUserId: number; amount: number; }
export interface GroupBalances {
  groupId: number; net: Record<string, number>; owes: PairDebt[]; simplified: PairDebt[];
}
export interface UserBalances {
  userId: number; totalOwed: number; totalReceivable: number; breakdown: PairDebt[];
}

export interface Settlement {
  id: number; groupId: number | null; fromUserId: number; toUserId: number;
  amount: number; currency: string; status: SettlementStatus; utr: string | null;
  claimedByUserId: number | null; claimedAt: string | null;
  acknowledgedByUserId: number | null; acknowledgedAt: string | null;
  disputeReason: string | null; disputedAt: string | null; createdAt: string;
}

export interface Notification {
  id: number; userId: number; type: string; message: string;
  refType: string | null; refId: number | null; read: boolean; createdAt: string;
}

export type Cadence = "WEEKLY" | "MONTHLY" | "CUSTOM";
export type ObligationStatus = "UPCOMING" | "DUE" | "OVERDUE" | "SETTLED";

export interface RecurringRule {
  id: number; groupId: number | null; createdBy: number; description: string; category: string;
  amount: number; currency: string; cadence: Cadence; intervalDays: number | null;
  nextDueDate: string; memberUserIds: number[]; active: boolean;
}

export interface PaymentObligation {
  id: number; recurringRuleId: number; dueDate: string; amount: number;
  currency: string; description: string; status: ObligationStatus; settlementId: number | null;
}
