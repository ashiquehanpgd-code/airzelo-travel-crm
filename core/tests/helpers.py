import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from server import services  # noqa: E402
from server.db import connect, migrate  # noqa: E402
from server.web import Context  # noqa: E402


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="qt-test-"))
        self.ctx = Context(self.tmp)
        self.ctx.init()
        self.conn = connect(self.ctx.db_path)
        self.cash = self.conn.execute("SELECT id FROM accounts WHERE name = 'Cash in hand'").fetchone()["id"]
        self.bank = services.save_account(self.conn, {"name": "HDFC Current", "kind": "bank", "currency": "INR"})
        self.sar_acc = services.save_account(self.conn, {"name": "SAR Cash", "kind": "cash", "currency": "SAR"})
        self.cust = services.create_customer(self.conn, {"name": "Ahmed Khan", "phone": "+91 98470 11111"})
        self.cust2 = services.create_customer(self.conn, {"name": "Fathima Rahman", "phone": "+91 90000 22222"})
        self.sup = services.create_supplier(self.conn, {"name": "Portal A", "kind": "b2b_portal"})
        self.sup2 = services.create_supplier(self.conn, {"name": "Portal B", "kind": "b2b_portal"})

    def tearDown(self):
        self.conn.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def booking(self, **kw):
        data = {
            "customer_id": self.cust, "supplier_id": self.sup, "pnr": kw.pop("pnr", "ABC123"),
            "booking_date": kw.pop("booking_date", "2026-10-10"), "status": kw.pop("status", "ticketed"),
            "currency": kw.pop("currency", "INR"), "exchange_rate": kw.pop("exchange_rate", "1"),
            "purchase_cost": kw.pop("purchase_cost", "10000"), "selling_price": kw.pop("selling_price", "12000"),
            "commission": kw.pop("commission", "0"), "service_charge": kw.pop("service_charge", "0"),
            "other_income": kw.pop("other_income", "0"),
            "passengers": kw.pop("passengers", [{"name": "AHMED KHAN", "pax_type": "ADT"}]),
            "segments": kw.pop("segments", [{"airline": "IndiGo", "flight_no": "6E 1407", "origin": "COK",
                                             "destination": "DXB", "dep_at": "2026-11-02T04:10", "arr_at": "2026-11-02T06:40"}]),
        }
        data.update(kw)
        return services.save_booking(self.conn, data)

    def txn(self, **kw):
        data = {"kind": "customer_receipt", "txn_date": "2026-10-11", "account_id": self.cash, "method": "cash"}
        data.update(kw)
        tid, _ = services.create_transaction(self.conn, data)
        return tid

    def calc(self, bid):
        return services.get_booking(self.conn, bid)["calc"]
