# Phone Keyboard (p2keyboard)

Type on an Android smart board (Newline etc.) with your phone's keyboard instead of the
oversized on-screen one. One APK, installed on both devices:

- **Board:** an input method (keyboard) with no keys. When you tap a text box, it shows a QR code.
- **Phone:** scan the QR code, then type with **[PixelBoard](https://github.com/Akshayykadam/PixelBoard)**
  (Gboard with the Pixel's Gemini voice typing and AI writing tools). The text goes into the
  focused text box on the board.

Pairing and transport are peer-to-peer over **Tailscale's public DERP relays**, so phone and board
don't need to be on the same network and nothing needs port forwarding. Both sides connect out to
the same relay over HTTPS (port 443), which gets through NAT, guest Wi-Fi and school firewalls.

## Setup

**Board**
1. Install `app-release.apk`.
2. Open *Phone Keyboard*, tap **Enable keyboard**, then **Select keyboard**.
3. Tap any text box. Instead of a keyboard, the board shows a QR code.

**Phone**
1. Install the same `app-release.apk`, plus PixelBoard
   ([releases](https://github.com/Akshayykadam/PixelBoard/releases/latest)), and enable PixelBoard as a keyboard.
2. Open *Phone Keyboard* → **Open phone keyboard** → **Scan board**. You can also scan the QR code
   with the stock camera: `p2k://` links open the app directly.
3. Type. The app reminds you to switch to PixelBoard if a different keyboard is active.

On the board, **New code** (or **Unpair**) rotates the secret, which disconnects the phone and
invalidates the old QR code.

## How it works

```
 PixelBoard ──InputConnection──▶ RemoteInputView ──ops──▶ SecureLink ──DERP (TLS)──▶ derpNN.tailscale.com
 (phone)                         (local mirror)            NaCl box, E2E                       │
                                                                                               ▼
 board app's text box ◀──InputConnection── RemoteKeyboardService ◀── OpApplier ◀── SecureLink (board)
```

- **Keyboard relay:** the phone's typing surface (`RemoteInputView`) gives PixelBoard a real
  `InputConnection` over a local mirror of the board's text around the cursor, so suggestions,
  autocorrect, swipe and voice typing behave normally. Every call (`commitText`,
  `setComposingText`, `deleteSurroundingText`, `sendKeyEvent`, `performEditorAction`, selection and
  composing-region changes) is forwarded and replayed on the board's field by the board IME
  (`OpApplier`). The board sends back the focused field's type (email, number, password, the
  Search/Go/Send action) so the phone keyboard switches layout to match.
- **DERP:** `DerpClient` speaks Tailscale's DERP protocol (HTTP upgrade, then `ServerKey` /
  `ClientInfo` handshake and `SendPacket` / `RecvPacket` frames). The board picks the nearest
  region from the public map at `login.tailscale.com/derpmap/default` and puts that relay's host
  in the QR code.
- **Security:** each device has a Curve25519 key. The QR code holds the board's public key, the
  relay host and a random 128-bit secret. Every message is sealed end-to-end with NaCl box
  (X25519 + XSalsa20-Poly1305) and carries an increasing sequence number, so the relay sees only
  ciphertext and can't replay keystrokes. The board accepts only a phone that presents the
  secret, and password fields never send their contents to the phone.

## About "Gboard source"

Gboard is closed source. PixelBoard isn't a source tree either: it's a Morphe/ReVanced patch bundle
applied to Google's Gboard APK (v18.6.3.100, arm64), installed as `com.akshaykadam.pixelboard`.
This project doesn't copy or redistribute Gboard. It uses PixelBoard, unmodified, as the
phone-side keyboard, through the standard Android input method interface, so any keyboard works,
with PixelBoard recommended.

## Code

| Path | What |
| --- | --- |
| `core/DerpClient.java` | Tailscale DERP protocol client |
| `core/NaclBox.java` | NaCl `crypto_box` (BouncyCastle primitives), verified against tweetnacl and the NaCl spec vectors |
| `core/SecureLink.java` | Reconnecting, end-to-end encrypted, replay-protected DERP link |
| `core/BoardSession.java`, `core/PhoneSession.java` | Pairing handshake, liveness, unpairing |
| `core/RemoteOps.java` | Wire format for keyboard operations |
| `board/RemoteKeyboardService.java`, `board/OpApplier.java` | Board IME: QR code UI and replaying ops |
| `phone/PhoneActivity.java`, `phone/RemoteInputView.java` | Phone: scanner, PixelBoard detection, the forwarding InputConnection |

## Build

Needs JDK 17 and the Android SDK (platform 34).

```
gradle testDebugUnitTest assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

The release APK is minified and signed with the debug key so it can be sideloaded; use your own
signing config for distribution. GitHub Actions builds it on every push.

Tests: `CoreTest` covers the crypto vectors, pairing codes, op parsing and DERP map parsing.
`RelayTest` (Robolectric) drives the phone's InputConnection with Gboard-style sequences and checks
a real `EditText` on the "board" ends up with the same text. If Robolectric can't download its
Android jar, set `ROBOLECTRIC_DEPS_DIR` to a folder containing it.

## Notes

- Tailscale runs the public DERP servers. Heavy or commercial use should run your own `derper`
  and put its host in the QR code (`Pairing.host`).
- Typing latency is the round trip to the relay region: roughly 30–120 ms.
