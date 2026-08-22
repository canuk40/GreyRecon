# Provenance

This module's source is vendored from
[`canuk40/termux-kotlin-app`](https://github.com/canuk40/termux-kotlin-app)
(a Kotlin conversion of [`termux/termux-app`](https://github.com/termux/termux-app)),
specifically its `terminal-emulator` module.

`termux-app` as a whole is licensed GPLv3-only. Its own
[`LICENSE.md`](https://github.com/termux/termux-app/blob/master/LICENSE.md) carries an explicit,
named exception for this module:

> "[Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is
> used which is released under Apache 2.0 license. Check `terminal-view` and `terminal-emulator`
> libraries."

That exception traces back to `jackpal/Android-Terminal-Emulator` (Apache-2.0), the historical
upstream this module descended from. See [`LICENSE-APACHE-2.0.txt`](LICENSE-APACHE-2.0.txt) in this
directory for the full license text. This module -- unlike the rest of the termux-app repository --
is used here under that Apache-2.0 grant, not GPLv3.
