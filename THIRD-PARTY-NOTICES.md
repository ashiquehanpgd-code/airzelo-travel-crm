# Third-party components (draft — verify before each commercial release)

| Component | Version | Shipped in the app? | Licence | Notes |
|---|---|---|---|---|
| CPython (via python-build-standalone) | 3.12.15 (release 20261003) | Yes (`Resources/python`) | PSF License Agreement; bundled libraries include OpenSSL (Apache-2.0), SQLite (public domain), zlib, libffi (MIT), bzip2, xz, mpdecimal, expat | Include CPython's `LICENSE.txt` and the bundled-library licences from the runtime in the installer before release |
| Electron (with Chromium, Node.js) | 44.7.0 | Yes | MIT; Chromium components under BSD-style and other licences (see `LICENSES.chromium.html` in the app) | |
| electron-builder | 26.17.0 | No (build tool) | MIT | |
| Apple PDFKit / Vision | macOS system frameworks | No (used from the OS) | Apple SDK terms | macOS ticket reader only |

All other code is the publisher's own (derived from the Quick Trip CRM project). It uses only the Python standard library and has no third-party fonts, icons or web libraries.

**Before release:** a legal review of the EULA and privacy policy; confirmation of ownership of the source code; this list re-checked against the final build (for example with a licence scanner on `node_modules` in production mode and the runtime folder).
