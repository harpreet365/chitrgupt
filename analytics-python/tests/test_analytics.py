from datetime import date, datetime, timezone
from decimal import Decimal

from fastapi.testclient import TestClient

from app import main


def txn(ref, type_, amount, status="COMPLETED", src=None, dst=None, day=1):
    return main.Txn(reference=ref, type=type_, status=status, amount=Decimal(amount), sourceAccountId=src,
                    sourceAccountNumber=f"A{src}" if src else None, destinationAccountId=dst,
                    destinationAccountNumber=f"A{dst}" if dst else None,
                    createdAt=datetime(2026, 10, day, 6, 0, tzinfo=timezone.utc))


SAMPLE = [
    txn("T1", "DEPOSIT", "100.00", dst=1, day=1),
    txn("T2", "WITHDRAWAL", "40.00", src=1, day=1),
    txn("T3", "TRANSFER", "25.50", src=1, dst=2, day=2),
    txn("T4", "WITHDRAWAL", "999.00", status="FAILED", src=2, day=2),
]


def test_totals_ignore_failed_transactions():
    t = main.totals(SAMPLE)
    assert t["completedCount"] == 3 and t["failedCount"] == 1
    assert t["volume"] == "165.50"
    assert t["depositTotal"] == "100.00" and t["transferTotal"] == "25.50"


def test_daily_buckets_include_empty_days():
    buckets = main.periodic(SAMPLE, date(2026, 10, 1), date(2026, 10, 3), "daily")
    assert [b["period"] for b in buckets] == ["2026-10-01", "2026-10-02", "2026-10-03"]
    assert buckets[2]["transactionCount"] == 0


def test_endpoints_require_service_token(monkeypatch):
    monkeypatch.setattr(main, "SERVICE_TOKEN", "x" * 32)
    monkeypatch.setattr(main, "fetch", lambda s, e, a=None: SAMPLE)
    client = TestClient(main.app)
    assert client.get("/api/v1/summary?from=2026-10-01&to=2026-10-03").status_code == 401
    ok = client.get("/api/v1/summary?from=2026-10-01&to=2026-10-03", headers={"X-Service-Token": "x" * 32})
    assert ok.status_code == 200 and ok.json()["volume"] == "165.50"
    acct = client.get("/api/v1/accounts/1/summary?from=2026-10-01&to=2026-10-03", headers={"X-Service-Token": "x" * 32})
    assert acct.json()["moneyIn"] == "100.00" and acct.json()["moneyOut"] == "65.50"
    bad = client.get("/api/v1/summary?from=2026-10-05&to=2026-10-01", headers={"X-Service-Token": "x" * 32})
    assert bad.status_code == 422
