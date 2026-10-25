# Android UI/UX redesign (plan and design system)

Source of truth for content: the desktop web UI (`core/static/js/dashboard.js`, `bookings.js`, `finance.js`,
`people.js`, `reports.js`, `core.js` labels) and its design tokens (`core/static/app.css`). The Android engine
(`android/engine`) is unchanged: every number on screen comes from the same Ledger / services as the desktop.

## Design tokens (`ui/Theme.kt`)

| Token | Light | Dark | Use |
|---|---|---|---|
| navy / navy-2 | #0A1A3F / #0F2457 | same | brand surfaces, hero gradients, top bars |
| royal | #2350E6 | #4C74FF | primary actions, links, selected states |
| cyan | #19B4D8 | #3BCBEB | secondary accent, cash-in |
| success / warn / danger / violet | #0E9F6B / #C27A06 / #D33447 / #6B4CE6 | brighter | money direction, statuses |
| bg / surface / surface-2 / border | #F3F6FB / #FFF / #F7F9FD / #E2E8F2 | #070D1D / #0D1630 / #111C3A / #1E2A4C | pages, cards, inputs |
| text / text-2 / muted | #0D1833 / #33415F / #64738F | #E7EDFB / #C3CDE6 / #8B98B8 | hierarchy |

Shape: cards 18 dp, inputs and chips 12 dp, pills full. Spacing scale 4/8/12/16/20/24. Elevation: hairline border
plus a soft 2-6 dp shadow (no heavy Material elevation). Typography: system sans; display 28/30 sp bold for hero
figures, title 17-20 sp semibold, body 14-15 sp, labels 11-12 sp medium with letter spacing. Money uses tabular figures.
Status colours and labels are the desktop's (`statusCls`, `payCls`, `labels.pay`). Light and dark themes follow the
phone setting.

## Components (`ui/Kit.kt`, `ui/Charts.kt`)

App bar (title, subtitle, actions), hero gradient card, KPI card, section header (sticky in long lists), list rows
with avatar / icon chip, status and payment chips, money text, filter chips row, search field, period selector,
form fields (text, amount, phone, email, caps; date with a calendar picker, date-time with date + time pickers,
dropdown), primary / secondary / destructive buttons, empty / loading / error states, confirmation dialogs,
snackbar-style success messages. Charts: revenue bars + gross-profit line (the desktop's chart buckets), horizontal
breakdown bars (the desktop's profit breakdown), status distribution bars.

## Navigation

Bottom bar: Home, Bookings, Customers, Money, More. More is a grouped grid: Books (reports, invoices & credit notes,
suppliers, cash & bank, cashbook, expenses, refunds & cancellations), Safety (backup & restore), Settings (company &
documents, online features, about & security). Tab state (scroll position, search, filters) is kept when switching
tabs; detail screens keep their place behind the app lock.

## Dashboard (desktop `dashboard.js`, adapted to a phone, in priority order)

1. Greeting, business name, date; period selector (Today, Week, Month, FY, Year, Custom range).
2. Hero card: sales for the period, bookings, gross profit and margin.
3. Quick actions (swipe): new booking, record payment, find customer, add expense, invoices, reports.
4. KPI cards (swipe): the desktop's twelve - net profit, payments received, customer outstanding, supplier
   outstanding, ticket purchase cost, markup, commission, operating expenses, refunds & cancellations, net cash flow
   (sales and gross profit are in the hero). Each opens its report or list, as on the desktop.
5. Today / this month / this year cards.
6. Revenue & profit chart; profit breakdown with net profit and tax collected.
7. Booking status and payment status summary (counts from the same ledger; new for the phone).
8. Pending customer payments, upcoming departures (14 days), follow-ups.
9. Recent bookings, cash & bank balances, recent transactions.

## Scrolling and performance

Every list and long screen is a `LazyColumn` (dashboard, lists, reports, statements) with stable keys; horizontal
sections are `LazyRow`; More is a `LazyVerticalGrid`. Forms are single (non-lazy) scroll columns so no field is ever
disposed while typing. Ledger work is computed once per change (keyed on the data revision and filters), not on every
recomposition. Large reports show every row through the lazy list instead of a 300-row cap.

## Screenshots and tests

Robolectric (native graphics) renders the main screens with synthetic demo data at phone sizes and saves PNGs as a
CI artifact; the debug build can also be screenshotted on the emulator (release builds keep FLAG_SECURE always).
UI tests: dashboard shows the ledger's real figures, every tab and More entry opens, long lists and the dashboard
scroll to the end, booking form keeps values while scrolling, booking + payment workflow, backup / restore.
