"""BMS analytics service (educational simulation).

Read-only: it pulls a minimal transaction feed from the Java backend's /internal/v1 API (service token,
loopback only) and computes statistics. It never writes to the database or touches balances.
Only the Java backend calls this service; every request must carry X-Service-Token.
"""
from __future__ import annotations

import os
import secrets
from collections import defaultdict
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from typing import Literal
from zoneinfo import ZoneInfo

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel

JAVA_URL = os.environ.get("JAVA_INTERNAL_URL", "http://127.0.0.1:8080")
INTERNAL_TOKEN = os.environ.get("INTERNAL_API_TOKEN", "")          # Python -> Java
SERVICE_TOKEN = os.environ.get("ANALYTICS_SERVICE_TOKEN", "")      # Java -> Python
TZ = ZoneInfo(os.environ.get("BUSINESS_TIMEZONE", "Asia/Kolkata"))
MAX_DAYS = 400

app = FastAPI(title="BMS Analytics", version="1.0.0", docs_url=None, redoc_url=None)


class Txn(BaseModel):
    """One feed item; validated so malformed upstream data is rejected, not silently used."""
    reference: str
    type: Literal["DEPOSIT", "WITHDRAWAL", "TRANSFER"]
    status: Literal["COMPLETED", "FAILED"]
    amount: Decimal
    sourceAccountId: int | None = None
    sourceAccountNumber: str | None = None
    destinationAccountId: int | None = None
    destinationAccountNumber: str | None = None
    createdAt: datetime


def require_token(x_service_token: str = Header(default="")) -> None:
    if len(SERVICE_TOKEN) < 16 or not secrets.compare_digest(x_service_token, SERVICE_TOKEN):
        raise HTTPException(status_code=401, detail="invalid service token")


def check_range(start: date, end: date) -> None:
    if start > end or (end - start).days > MAX_DAYS:
        raise HTTPException(status_code=422, detail=f"invalid date range (max {MAX_DAYS} days)")


def fetch(start: date, end: date, account_id: int | None = None) -> list[Txn]:
    """Pull all transactions in [start, end] (business-timezone days) from Java, page by page."""
    params = {
        "from": datetime.combine(start, time.min, TZ).isoformat(),
        "to": datetime.combine(end + timedelta(days=1), time.min, TZ).isoformat(),
        "size": 1000,
    }
    if account_id is not None:
        params["accountId"] = account_id
    items: list[Txn] = []
    page = 0
    try:
        with httpx.Client(base_url=JAVA_URL, timeout=10, headers={"X-Service-Token": INTERNAL_TOKEN}) as client:
            while True:
                r = client.get("/internal/v1/transactions", params={**params, "page": page})
                r.raise_for_status()
                body = r.json()
                items.extend(Txn.model_validate(i) for i in body["items"])
                if body["last"]:
                    return items
                page += 1
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail="backend feed unavailable") from e


def money(d: Decimal) -> str:
    return str(d.quantize(Decimal("0.01")))


def totals(txns: list[Txn]) -> dict:
    done = [t for t in txns if t.status == "COMPLETED"]
    by_type = {k: Decimal(0) for k in ("DEPOSIT", "WITHDRAWAL", "TRANSFER")}
    counts = {k: 0 for k in by_type}
    for t in done:
        by_type[t.type] += t.amount
        counts[t.type] += 1
    volume = sum(by_type.values(), Decimal(0))
    return {
        "transactionCount": len(txns),
        "completedCount": len(done),
        "failedCount": len(txns) - len(done),
        "volume": money(volume),
        "averageAmount": money(volume / len(done)) if done else "0.00",
        "depositTotal": money(by_type["DEPOSIT"]),
        "withdrawalTotal": money(by_type["WITHDRAWAL"]),
        "transferTotal": money(by_type["TRANSFER"]),
        "countsByType": counts,
        "currency": "INR",
    }


def bucket_key(d: date, period: str) -> date:
    if period == "weekly":
        return d - timedelta(days=d.weekday())   # Monday
    if period == "monthly":
        return d.replace(day=1)
    return d


def periodic(txns: list[Txn], start: date, end: date, period: str) -> list[dict]:
    groups: dict[date, list[Txn]] = defaultdict(list)
    for t in txns:
        groups[bucket_key(t.createdAt.astimezone(TZ).date(), period)].append(t)
    keys, k = [], bucket_key(start, period)
    while k <= end:                               # include empty buckets so charts have no gaps
        keys.append(k)
        k = (k + timedelta(days=32)).replace(day=1) if period == "monthly" else k + timedelta(days=7 if period == "weekly" else 1)
    return [{"period": key.isoformat(), **totals(groups.get(key, []))} for key in keys]


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/api/v1/summary", dependencies=[Depends(require_token)])
def summary(from_: date = Query(alias="from"), to: date = Query()) -> dict:
    check_range(from_, to)
    return {"from": from_, "to": to, **totals(fetch(from_, to))}


@app.get("/api/v1/stats/periodic", dependencies=[Depends(require_token)])
def stats(period: Literal["daily", "weekly", "monthly"], from_: date = Query(alias="from"), to: date = Query()) -> dict:
    check_range(from_, to)
    return {"period": period, "buckets": periodic(fetch(from_, to), from_, to, period)}


@app.get("/api/v1/trends", dependencies=[Depends(require_token)])
def trends(days: int = Query(30, ge=7, le=365)) -> dict:
    end = datetime.now(TZ).date()
    start = end - timedelta(days=days - 1)
    buckets = periodic(fetch(start, end), start, end, "daily")
    vols = [float(b["volume"]) for b in buckets]
    first, second = vols[: len(vols) // 2], vols[len(vols) // 2:]
    avg = lambda xs: sum(xs) / len(xs) if xs else 0.0  # noqa: E731
    change = ((avg(second) - avg(first)) / avg(first) * 100) if avg(first) else None
    return {"days": days, "series": [{"date": b["period"], "volume": b["volume"], "count": b["completedCount"]} for b in buckets],
            "averageDailyVolume": round(avg(vols), 2),
            "changePercent": round(change, 1) if change is not None else None}


@app.get("/api/v1/accounts/top", dependencies=[Depends(require_token)])
def top_accounts(from_: date = Query(alias="from"), to: date = Query(), limit: int = Query(10, ge=1, le=50)) -> dict:
    check_range(from_, to)
    stats: dict[str, dict] = {}
    for t in fetch(from_, to):
        if t.status != "COMPLETED":
            continue
        for number in {t.sourceAccountNumber, t.destinationAccountNumber} - {None}:
            s = stats.setdefault(number, {"accountNumber": number, "count": 0, "volume": Decimal(0)})
            s["count"] += 1
            s["volume"] += t.amount
    ranked = sorted(stats.values(), key=lambda s: (s["count"], s["volume"]), reverse=True)[:limit]
    return {"accounts": [{**s, "volume": money(s["volume"])} for s in ranked]}


@app.get("/api/v1/accounts/{account_id}/summary", dependencies=[Depends(require_token)])
def account_summary(account_id: int, from_: date = Query(alias="from"), to: date = Query()) -> dict:
    """Called by Java only after Java has verified the caller may see this account."""
    check_range(from_, to)
    money_in = money_out = Decimal(0)
    count = 0
    for t in fetch(from_, to, account_id):
        if t.status != "COMPLETED":
            continue
        count += 1
        if t.destinationAccountId == account_id:
            money_in += t.amount
        if t.sourceAccountId == account_id:
            money_out += t.amount
    return {"accountId": account_id, "from": from_, "to": to, "transactionCount": count,
            "moneyIn": money(money_in), "moneyOut": money(money_out), "net": money(money_in - money_out)}


@app.get("/api/v1/reports/admin", dependencies=[Depends(require_token)])
def admin_report(period: Literal["daily", "weekly", "monthly"], date_: date = Query(alias="date")) -> dict:
    start = bucket_key(date_, period)
    end = start if period == "daily" else (start + timedelta(days=6) if period == "weekly"
                                           else (start + timedelta(days=32)).replace(day=1) - timedelta(days=1))
    txns = fetch(start, end)
    return {"period": period, "from": start, "to": end, "summary": totals(txns),
            "breakdown": periodic(txns, start, end, "daily"),
            "generatedAt": datetime.now(TZ).isoformat(), "notice": "Educational simulation - not real financial data."}
