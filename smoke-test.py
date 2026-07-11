#!/usr/bin/env python3
"""End-to-end smoke test for Squarely. Exercises the real running services:
signup -> group -> expense (Kafka -> ledger) -> balances/simplify -> settlement
handshake (create/claim/ack) with idempotency + a concurrency/invalid-transition check.
Stdlib only. Exits non-zero on the first failed assertion."""
import json, time, urllib.request, urllib.error, uuid

AUTH, GROUP, LEDGER, NOTIFY = 8081, 8082, 8083, 8084
PASS = "\033[92mPASS\033[0m"

def call(port, path, method="GET", token=None, body=None, headers=None, expect=None):
    url = f"http://localhost:{port}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", f"Bearer {token}")
    for k, v in (headers or {}).items(): req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as r:
            status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    if expect is not None and status != expect:
        raise AssertionError(f"{method} {url} -> {status} (expected {expect})\n{text}")
    return status, (json.loads(text) if text.strip() else None)

def check(name, cond, detail=""):
    if not cond: raise AssertionError(f"FAILED: {name} {detail}")
    print(f"  {PASS}  {name}  {detail}")

print("== signup two users ==")
priya_email = f"priya-{uuid.uuid4().hex[:8]}@x.com"
rahul_email = f"rahul-{uuid.uuid4().hex[:8]}@x.com"
_, p = call(AUTH, "/auth/signup", "POST", body={"email": priya_email, "password": "password123", "displayName": "Priya"}, expect=201)
_, r = call(AUTH, "/auth/signup", "POST", body={"email": rahul_email, "password": "password123", "displayName": "Rahul"}, expect=201)
priya_tok, rahul_tok = p["accessToken"], r["accessToken"]
_, pme = call(AUTH, "/auth/me", token=priya_tok, expect=200)
_, rme = call(AUTH, "/auth/me", token=rahul_tok, expect=200)
priya_id, rahul_id = pme["id"], rme["id"]
check("signup + JWT + /me", priya_id != rahul_id, f"priya={priya_id} rahul={rahul_id}")

print("== refresh token rotation ==")
_, refreshed = call(AUTH, "/auth/refresh", "POST", body={"refreshToken": p["refreshToken"]}, expect=200)
check("refresh issues a token pair", "accessToken" in refreshed and "refreshToken" in refreshed)
# the rotated-away (old) refresh token must no longer work
st, _ = call(AUTH, "/auth/refresh", "POST", body={"refreshToken": p["refreshToken"]})
check("old refresh token revoked after rotation (401)", st == 401, f"got {st}")
priya_tok = refreshed["accessToken"]

print("== group + members ==")
_, g = call(GROUP, "/groups", "POST", token=priya_tok, body={"name": "Goa Trip"}, expect=201)
gid = g["id"]
call(GROUP, f"/groups/{gid}/members", "POST", token=priya_tok, body={"userId": rahul_id}, expect=201)
_, members = call(GROUP, f"/groups/{gid}/members", token=priya_tok, expect=200)
check("group has 2 members", len(members) == 2, f"members={[m['userId'] for m in members]}")

print("== add equally-split expense (Priya pays 3000, split with Rahul) ==")
_, exp = call(GROUP, f"/groups/{gid}/expenses", "POST", token=priya_tok, body={
    "description": "Hotel", "category": "TRAVEL", "amount": 3000, "currency": "INR",
    "paidByUserId": priya_id, "splitType": "EQUAL",
    "participants": {str(priya_id): 0, str(rahul_id): 0}}, expect=201)
check("expense split equally", exp["splits"][str(rahul_id)] == 1500.00, f"rahul owes {exp['splits'][str(rahul_id)]}")

print("== wait for Kafka -> ledger to record the debt ==")
owed = None
for _ in range(20):
    time.sleep(1)
    _, bal = call(LEDGER, f"/balances/group/{gid}", token=priya_tok, expect=200)
    if bal["net"]:
        owed = bal
        break
check("ledger recorded expense via Kafka", owed is not None, "(consumed expense.added)")
check("Priya is owed 1500 net", float(owed["net"][str(priya_id)]) == 1500.0, f"net={owed['net']}")
check("simplified: Rahul -> Priya 1500", any(
    t["fromUserId"] == rahul_id and t["toUserId"] == priya_id and t["amount"] == 1500.00
    for t in owed["simplified"]), f"simplified={owed['simplified']}")

print("== settlement create with idempotency (Rahul pays Priya 1500) ==")
idem = str(uuid.uuid4())
body = {"groupId": gid, "fromUserId": rahul_id, "toUserId": priya_id, "amount": 1500, "currency": "INR"}
_, s1 = call(LEDGER, "/settlements", "POST", token=rahul_tok, body=body, headers={"Idempotency-Key": idem}, expect=201)
_, s2 = call(LEDGER, "/settlements", "POST", token=rahul_tok, body=body, headers={"Idempotency-Key": idem}, expect=201)
check("idempotency: same settlement on retry", s1["id"] == s2["id"], f"id={s1['id']}")
sid = s1["id"]

print("== settlement handshake ==")
_, sc = call(LEDGER, f"/settlements/{sid}/claim", "POST", token=rahul_tok, body={"utr": "UPI123456"}, expect=200)
check("payer claim -> PAYMENT_CLAIMED", sc["status"] == "PAYMENT_CLAIMED", f"utr={sc['utr']}")
# receiver-only guard: Rahul (payer) cannot acknowledge his own claim
st, _ = call(LEDGER, f"/settlements/{sid}/acknowledge", "POST", token=rahul_tok)
check("payer cannot acknowledge (403)", st == 403, f"got {st}")
_, sa = call(LEDGER, f"/settlements/{sid}/acknowledge", "POST", token=priya_tok, expect=200)
check("receiver acknowledge -> SETTLED", sa["status"] == "SETTLED")
# invalid transition: acknowledging an already-settled payment
st, _ = call(LEDGER, f"/settlements/{sid}/acknowledge", "POST", token=priya_tok)
check("cannot settle an already-settled payment (409)", st == 409, f"got {st}")

print("== balances zero out after settlement ==")
_, bal2 = call(LEDGER, f"/balances/group/{gid}", token=priya_tok, expect=200)
check("group fully settled (no net balances)", bal2["net"] == {}, f"net={bal2['net']}")

print("== notifications generated via Kafka ==")
prcv = None
for _ in range(15):
    time.sleep(1)
    _, notes = call(NOTIFY, "/notifications", token=priya_tok, expect=200)
    types = {n["type"] for n in notes}
    if "EXPENSE_ADDED" in types and "PAYMENT_CLAIMED" in types:
        prcv = types
        break
check("Priya notified of expense + claim", prcv and {"EXPENSE_ADDED", "PAYMENT_CLAIMED"} <= prcv, f"types={prcv}")
_, rnotes = call(NOTIFY, "/notifications", token=rahul_tok, expect=200)
check("Rahul notified of acknowledgement", "PAYMENT_ACKNOWLEDGED" in {n["type"] for n in rnotes})

print("== personal debt (no group) ==")
_, pd = call(LEDGER, "/personal-debts", "POST", token=rahul_tok, body={
    "debtorId": rahul_id, "creditorId": priya_id, "amount": 1000, "currency": "INR"}, expect=201)
check("personal debt recorded", pd["type"] == "PERSONAL_DEBT" and pd["groupId"] is None)

print("== recurring rule + tick ==")
_, rule = call(NOTIFY, "/recurring", "POST", token=priya_tok, body={
    "groupId": gid, "description": "Flat Rent", "category": "RENT", "amount": 20000, "currency": "INR",
    "cadence": "MONTHLY", "firstDueDate": time.strftime("%Y-%m-%d"),
    "memberUserIds": [priya_id, rahul_id]}, expect=201)
call(NOTIFY, "/recurring/run", "POST", token=priya_tok, expect=204)
_, obs = call(NOTIFY, "/obligations", token=priya_tok, expect=200)
check("recurring tick generated an obligation", any(o["recurringRuleId"] == rule["id"] for o in obs),
      f"statuses={[o['status'] for o in obs]}")

print("\n\033[92m==== ALL SMOKE TESTS PASSED ====\033[0m")
