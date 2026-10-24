import XCTest

/// Drives the real app UI offline: create a booking for a new customer, record the customer's payment,
/// and check the booking shows as paid. Uses throwaway books (-uitest, DEBUG builds only).
final class SmokeUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    var dumped = Set<String>()

    /// On failure: the full accessibility tree in the log and a screenshot in the result bundle.
    func diagnose(_ what: String, _ app: XCUIApplication) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = what
        shot.lifetime = .keepAlways
        add(shot)
        print("---- UI hierarchy (\(what)) ----\n\(app.debugDescription)")
    }

    /// Finds an element in a lazy form: rows exist only near the screen, so look in both directions,
    /// a little at a time, instead of flinging to the end (which releases the rows we passed).
    func reveal(_ el: XCUIElement, in app: XCUIApplication, _ what: String) -> Bool {
        if el.waitForExistence(timeout: 3) && el.isHittable { return true }
        if !dumped.contains(what) { dumped.insert(what); diagnose("\(what) not visible yet", app) }
        let scroller = app.collectionViews.firstMatch.exists ? app.collectionViews.firstMatch : app.scrollViews.firstMatch
        for direction in ["up", "down"] {
            for _ in 0..<8 {
                if el.exists && el.isHittable { return true }
                let from = scroller.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: direction == "up" ? 0.7 : 0.3))
                let to = scroller.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: direction == "up" ? 0.4 : 0.6))
                from.press(forDuration: 0.05, thenDragTo: to)
            }
        }
        return el.exists && el.isHittable
    }

    /// Like a user: put away the keyboard before moving on to the next field.
    func dismissKeyboard(_ app: XCUIApplication) {
        guard app.keyboards.firstMatch.exists else { return }
        for key in ["return", "Return", "done", "Done"] where app.keyboards.buttons[key].exists {
            app.keyboards.buttons[key].tap()
            return
        }
        app.navigationBars.firstMatch.tap()  // decimal pad has no return key
    }

    func type(_ id: String, _ text: String, in app: XCUIApplication) {
        let f = app.textFields[id]
        guard reveal(f, in: app, "field \(id)") else {
            diagnose("missing field \(id)", app)
            XCTFail("field \(id) not found")
            return
        }
        f.tap()
        f.typeText(text)
        dismissKeyboard(app)
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
        XCTAssertTrue(reveal(pay, in: app, "record payment"), "Record customer payment button")
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
