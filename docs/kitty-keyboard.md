# Basic kitty keyboard protocol support

The terminal supports the disambiguate escape codes enhancement (flag `1`). It
does not implement repeat/release reporting, alternate key codes, reporting all
keys, or associated text. Unsupported flags are ignored and are never reported
as enabled by a query.

Applications negotiate support using `CSI ? u`, enable it using `CSI > 1 u`, and
restore the previous mode using `CSI < u`. Direct flag updates (`CSI = flags ;
mode u`) and popping multiple entries are supported. The main and alternate
screens have independent stacks, limited to 32 entries including the current mode. Terminal reset
clears both stacks. Without negotiation the existing keyboard encoding is used.

Examples after enabling flag `1`:

| Input | Bytes sent |
| --- | --- |
| Ctrl+0 | `ESC [ 48 ; 5 u` |
| Ctrl+I | `ESC [ 105 ; 5 u` |
| Ctrl+Shift+A | `ESC [ 97 ; 6 u` |
| Shift+Enter | `ESC [ 13 ; 2 u` |
| Ctrl+Enter | `ESC [ 13 ; 5 u` |
| Escape | `ESC [ 27 u` |
| Plain Tab / Enter / Backspace | Existing recovery control bytes |

Hardware shortcuts use the active Android layout's unshifted Unicode character.
Shift, Alt, Ctrl and Android Meta (mapped to Super) are encoded. Num Lock and
Caps Lock are included for functional keys; keypad keys use dedicated codes.
Plain text, IME commits and paste retain their existing paths. Soft keyboard or
extra-key shortcuts encode the available character and modifiers; an IME text
commit cannot recover a physical key identity or its original modifier state.
Termux application shortcuts continue to take precedence over terminal input.
This includes the existing Shift+PageUp/PageDown scroll bindings. Main-screen
scrollback, alternate-screen arrow scrolling and mouse reporting retain their
existing scroll behavior. Space is resolved through the Android layout before
shortcut encoding, so right Alt/AltGr text is preserved; left Alt+Space and
Ctrl+Space still use negotiated keyboard encoding.

The program receiving the input must support the protocol. Intermediaries such
as terminal multiplexers also need to pass or translate the sequences correctly.

Specification: https://sw.kovidgoyal.net/kitty/keyboard-protocol/

## Validation status

### Review fixes (2026-10-07)

- Resolve printable Space through the keyboard layout before encoding; a
  synthetic AltGr layout now checks NBSP and Euro text, including Ctrl+AltGr
  modifier handling. Ctrl+Space and left Alt+Space keep their negotiated codes.
- Run the existing scroll actions before key encoding, so Shift+PageUp/PageDown
  retain precedence in both legacy and kitty mode. Other Page keys still reach
  the terminal application.
- Select functional-key encoding through one `KeyHandler.getCode` entry point
  used by the view and virtual Fn mappings. Original commits were reworded to
  follow the repository's commit-message requirements without changing their
  source trees; the original history is retained in the local
  `backup/kitty-pre-review-20261007` branch.
- All 150 terminal-emulator and 10 app tests passed locally (zero failures,
  errors or skips). The app suite includes four new regression tests for the
  above layout and shortcut cases. The AltGr mapping is a test fixture, not a
  claim of coverage for every Android keyboard layout.
- The revised APK (`0.118.0+kitty.65c625b2`, source `65c625b2`) was installed as
  an update on the Android 16 arm64 PTP-AN10 device, preserving application data.
  All 96 recorded checks passed; see
  [the review-fix device report](kitty-keyboard-review-device-validation.json).
- Of these, 83 checks delivered Android framework `KeyEvent` objects directly
  to the installed production `TerminalView` through a temporary instrumentation
  component, then checked the real session queue, native PTY and shell capture.
  They include Ctrl/left-Alt+Space, page-key delivery, primary-screen scrollback
  offsets, and alternate-screen arrow scrolling. These are not physical HID or
  OS input-dispatch tests.
- Two more checks verified right Alt+Space against the current virtual keyboard
  mapping in kitty and legacy modes. That mapping returns no character; both
  modes correctly sent no bytes. This does not exercise an NBSP-producing layout.
- Eleven checks used real touches on the Rime keyboard and Termux CTRL/ALT extra
  keys, including Space combinations, one-shot modifier clearing, Enter,
  background/foreground and keyboard close/open. Capture setup used temporary
  instrumentation to avoid Chinese composition of the startup command.
- The user reports completing manual combination retests on this revision across
  on-device/external physical keyboards, local Termux/SSH, and Zellij/tmux.
  This is user-reported verification; tool versions and raw captures were not
  supplied. The earlier manual report below remains historical evidence.
- The external-keyboard AltGr/NBSP case remains uncovered; the combination
  retest report does not specifically confirm a character-producing AltGr layout.

Remaining manual verification is an AltGr+Space layout that produces NBSP or
another layout character.

### Historical validation before review fixes

Validated on 2026-10-06 with an isolated temporary toolchain:

- Eclipse Temurin JDK 21.0.12.1, Gradle 9.2.1, Android API 36, Build Tools 36.0.0,
  and NDK 29.0.14206865.
- All 150 terminal-emulator unit tests passed, including the five negotiation,
  state restoration, screen isolation, parsing and encoding tests in
  `KittyKeyboardTest`.
- All six app unit tests passed, including four Robolectric API 28 tests in
  `KittyKeyboardInputTest`. Those tests exercise Android keyboard events through
  `TerminalView` into the session input queue for Ctrl+0, Ctrl+I, Ctrl+Shift+A,
  shifted punctuation, modified Enter/Tab, mode restoration, soft input, and
  application shortcut handling. They do not start a native PTY or run a shell.
- Debug APK assembly succeeded for arm64-v8a, armeabi-v7a, x86, x86_64 and the
  universal APK using the default apt-android-7 package variant.
- The initial test run caught F3 being treated as the Enter recovery key because
  both use parameter 13. Recovery handling now also checks the sequence suffix.
- On a physical PTP-AN10 device running Android 16 (arm64-v8a), the installed
  Termux APK passed 52 raw input byte comparisons: 26 combinations in kitty
  mode and the same 26 in legacy mode. Android's `input` command injected the
  key events; they passed through the production `TerminalView`, native PTY and
  a foreground shell capture. Mode queries also verified push/pop restoration
  and isolation between the main and alternate screens. The source code tested
  was commit `91ae4885`, with APK version `0.118.0+kitty.91ae4885`.
- See [the real-device report](kitty-keyboard-device-validation.json) for each
  expected and actual byte sequence. This is device execution evidence, not
  Bluetooth/USB keyboard hardware coverage.
- Seven additional checks passed using real touches on the device's Rime numeric
  keyboard and Termux CTRL extra key: Ctrl+0/1/2, plain 0 after one-shot CTRL
  clears, IME Enter, and modifier encoding after background/foreground and soft
  keyboard close/open. See [the IME report](kitty-keyboard-ime-validation.json).
  A temporary instrumentation component started the ASCII capture command to
  avoid Rime composing the setup command as Chinese; the checked input itself
  came from the real keyboard UI and crossed the native PTY. The component is
  removed after validation.
- The user additionally reports successful manual verification combining
  on-device and external physical keyboards, local Termux and SSH, and
  Zellij/tmux. Kitty-encoded input reached the receiver in the tested
  combinations. This is user-reported verification; tool versions and raw
  captures were not supplied.
- Broader keyboard layouts, IME composition and additional application or
  multiplexer versions are outside the recorded coverage. Text-only IME commits cannot expose
  physical Shift/Alt key identity that Android does not deliver to the terminal.
- `git diff --check` passed.

With the toolchain paths exported as `JAVA_HOME` and `ANDROID_HOME`, reproduce
the checks from the repository root:

```sh
./gradlew --no-daemon --max-workers=2 :terminal-emulator:testDebugUnitTest
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug
```

For temporary setups, keep `GRADLE_USER_HOME` and `ANDROID_USER_HOME` inside the
same temporary directory as the JDK, SDK and build checkout. After Gradle exits,
delete that directory to remove the toolchain, caches and build artifacts.
