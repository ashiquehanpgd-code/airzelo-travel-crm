import XCTest

/// Drives the real app UI offline: create a booking for a new customer, record the customer's payment,
/// and check the booking shows as paid. Uses throwaway books (-uitest, DEBUG builds only).
final class SmokeUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func reveal(_ el: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<10 where !(el.exists && el.isHittable) { app.swipeUp() }
    }

    func type(_ id: String, _ text: String, in app: XCUIApplication) {
        let f = app.textFields[id]
        reveal(f, in: app)
        XCTAssertTrue(f.waitForExistence(timeout: 5), "field \(id)")
        f.tap()
        f.typeText(text)
    }

    func testBookingAndPaymentOffline() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest"]
        app.launch()

        app.tabBars.buttons["Bookings"].tap()
        app.buttons["new-booking"].tap()
        type("new-customer-name", "UI Test Customer", in: app)
        type("pnr", "UIT001", in: app)
        type("selling-price", "12500", in: app)
        type("pax-name", "UI Test Passenger", in: app)
        app.buttons["save-booking"].tap()

        let row = app.staticTexts["UI Test Customer"]
        XCTAssertTrue(row.waitForExistence(timeout: 10), "booking listed")
        row.tap()
        let badge = app.staticTexts["payment-status"]
        XCTAssertTrue(badge.waitForExistence(timeout: 5))
        XCTAssertEqual(badge.label, "Unpaid")

        let pay = app.buttons["record-payment"]
        reveal(pay, in: app)
        pay.tap()
        let amount = app.textFields["txn-amount"]
        XCTAssertTrue(amount.waitForExistence(timeout: 5))
        XCTAssertEqual(amount.value as? String, "12500.00", "outstanding amount is suggested")
        app.buttons["save-transaction"].tap()
        for _ in 0..<10 where !(badge.exists && badge.isHittable) { app.swipeDown() }
        let paid = NSPredicate(format: "label == 'Paid'")
        expectation(for: paid, evaluatedWith: badge)
        waitForExpectations(timeout: 10)
    }
}
