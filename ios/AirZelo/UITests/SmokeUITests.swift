import XCTest

/// Drives the real app UI offline: create a booking for a new customer, record the customer's payment,
/// and check the booking shows as paid. Uses throwaway books (-uitest, DEBUG builds only).
final class SmokeUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Waits for the element; scrolls only when it exists but is off screen (lazy forms create rows as they scroll in).
    func reveal(_ el: XCUIElement, in app: XCUIApplication) {
        if el.waitForExistence(timeout: 3) && el.isHittable { return }
        for _ in 0..<8 where !(el.exists && el.isHittable) { app.swipeUp() }
    }

    /// On failure: the full accessibility tree in the log and a screenshot in the result bundle.
    func diagnose(_ what: String, _ app: XCUIApplication) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = what
        shot.lifetime = .keepAlways
        add(shot)
        print("---- UI hierarchy (\(what)) ----\n\(app.debugDescription)")
    }

    func type(_ id: String, _ text: String, in app: XCUIApplication) {
        let f = app.textFields[id]
        reveal(f, in: app)
        guard f.exists else {
            diagnose("missing field \(id)", app)
            XCTFail("field \(id) not found")
            return
        }
        f.tap()
        f.typeText(text)
    }

    func testBookingAndPaymentOffline() {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest", "-uitest-tab", "bookings"]
        app.launch()

        XCTAssertTrue(app.navigationBars["Bookings"].waitForExistence(timeout: 15), "Bookings screen")
        let add = app.navigationBars["Bookings"].buttons["New booking"]
        XCTAssertTrue(add.waitForExistence(timeout: 5), "New booking button")
        add.tap()
        type("new-customer-name", "UI Test Customer", in: app)
        type("pnr", "UIT001", in: app)
        type("selling-price", "12500", in: app)
        type("pax-name", "UI Test Passenger", in: app)
        app.buttons["Save booking"].tap()

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
        app.buttons["Save entry"].tap()
        for _ in 0..<10 where !(badge.exists && badge.isHittable) { app.swipeDown() }
        let paid = NSPredicate(format: "label == 'Paid'")
        expectation(for: paid, evaluatedWith: badge)
        waitForExpectations(timeout: 10)
    }
}
