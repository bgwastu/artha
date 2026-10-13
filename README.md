<p align="center"><img src="assets/icon.png" alt="Artha icon" width="96" height="96"></p>

<h1 align="center">Artha</h1>

Artha helps people in Indonesia understand their spending on rooted Android phones. When you open the app, it collects transactions from supported banking, shopping, and ride apps, removes duplicates, and groups spending by category.

## Features

- **Unified timeline:** Automatically collect transactions and reconcile duplicates across providers.
- **Order details:** See food items, shopping purchases, and ride routes.
- **Monthly spending:** Compare categories and totals with the previous month.
- **AI insights:** Use personal notes to refine categories and spending recommendations.
- **Search and filters:** Find transactions by keyword, provider, or date range.
- **Homescreen widgets:** Check recent transactions and monthly spending without opening the app.
- **Encrypted sync:** Back up and merge your ledger through Google Drive, encrypted on-device.

## Providers

- **Bank Jago:** Reads and decrypts transaction history cached by the Jago app using root access.
- **Shopee & ShopeeFood:** Uses the app's saved session cookies to fetch order history and match Jago payments.
- **Tokopedia:** Uses the app's saved login session to fetch order history and match Jago payments.
- **Gojek & GoPay:** Imports locally saved Gojek orders and payments identified in Jago history.
- **Grab:** Decrypts the app's local session to fetch ride routes, GrabFood orders, and GrabPay history directly.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/screenshot-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="assets/screenshot.png">
  <img src="assets/screenshot.png" alt="Artha transaction timeline and spending analysis with fictional sample data">
</picture>

Imports use provider caches and private endpoints, so available history can be incomplete. Settings reports limited coverage and separates importing from hiding a source. Imports currently support the primary Android profile; Tokopedia and Grab schemas remain experimental and uncertain payment fields are skipped. Saved data remains available when root or a source is unavailable.

AI disclosure: Human validated.
