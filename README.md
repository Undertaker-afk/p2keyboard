# Phone Keyboard (p2keyboard)

Use your phone's keyboard to type on an Android smart board (Newline, etc.).

Install the app on the **board** only. The phone needs nothing installed: it scans a QR code and
types into a web page, so you get your own Gboard (or any keyboard) with autocorrect, swipe,
voice typing and emoji.

## How it works

1. On the board, enable **Phone Keyboard** as an input method and select it (setup screen in the app).
2. Tap any text field on the board. Instead of an oversized on-screen keyboard it shows a QR code.
3. Scan it with the phone camera. The board serves a small typing page over the local network
   (`http://<board-ip>:<port>/?t=<secret>`); the phone connects back over a WebSocket.
4. Type on the phone; the text appears in the focused field on the board. Enter, Tab, arrows,
   Home/End and Backspace (including on an empty box) are forwarded too.

Pairing is peer-to-peer on the LAN: no cloud, no account, no relay. The QR contains a random
128-bit token; connections without it are refused. **New code** on the board revokes the old one
and drops the phone. Phone and board must be on the same Wi-Fi/LAN (a phone hotspot works too).

## Why not Gboard's source?

Gboard is proprietary; its source is not available. It isn't needed: the phone's own keyboard
does the typing, and the board just receives the resulting text.

## Layout

- `app/.../core/PairingServer.java` - dependency-free HTTP + WebSocket server (plain JVM, unit-tested)
- `app/.../board/RemoteKeyboardService.java` - the InputMethodService: QR UI, applies edits via `InputConnection`
- `app/src/main/assets/remote.html` - the page the phone opens
- Wire protocol is documented in the `PairingServer` javadoc.

## Build

Needs the Android SDK (API 34) and Gradle 8.7+: `gradle assembleDebug`
(the GitHub Actions workflow does this and uploads the APK). Sideload `app-debug.apk` on the board.

## Limits

- Text is sent as edits at the cursor (type, delete, retype); it can't move the board's cursor to
  re-edit earlier text except via the arrow keys.
- Traffic is plain HTTP/WS on the LAN, so anyone on the network who sees the QR/URL could pair.
  Use **New code** if the code was exposed.
