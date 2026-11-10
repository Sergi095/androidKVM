# Development

## Build

Requires JDK 17 and Android SDK platform 35. For a local test APK:

```sh
./gradlew assembleDebug
```

The debug APK is signed with a different key from releases, so it cannot update an installed release. Use a signed release APK for normal installation. The default KVM port is 443; add a different port to the address when needed.

## Releases

[GitHub Actions](../.github/workflows/android.yml) builds a debug APK on pushes and manual runs. A `v*` tag also builds a signed APK and uploads it to a GitHub Release. Set these repository secrets for tagged releases:

| Secret | Value |
| --- | --- |
| `ANDROIDKVM_KEYSTORE_BASE64` | Base64 of the release `.jks` file, without line breaks |
| `ANDROIDKVM_STORE_PASSWORD` | Keystore password |
| `ANDROIDKVM_KEY_ALIAS` | Key alias |
| `ANDROIDKVM_KEY_PASSWORD` | Key password |

Keep the release keystore safe: Android updates require the same signing key. The in-app updater verifies the downloaded APK's SHA-256 digest, package, version, and signing certificate before opening Android's installer. Uninstalling the app first removes saved KVMs.

## Connection details

- The app requires HTTPS and uses the KVM web login. Saved passwords are encrypted with a key in Android Keystore. Android backup is disabled. An unrecognized certificate must be explicitly trusted; the app then pins that certificate for the saved KVM. A changed certificate prompts again.
- For PiKVM with two-factor authentication, append the current one-time code to the password during login, as described in the [PiKVM API](https://docs.pikvm.org/api/).
- General PiKVM video uses MJPEG. If the [NanoKVM PiKVM bridge](https://github.com/Sergi095/nanokvm-pikvm-bridge) is on the same host as NanoKVM and shares its pinned certificate, the app can use NanoKVM's H.264 Direct stream. Audio and virtual media are not implemented.
- ATX buttons follow the KVM's reported power LED state. If the screen is live but the power LED is reported off, check the KVM's ATX sensing.
- Wake-on-LAN is sent by the KVM, not the phone. NanoKVM uses its native WOL API. PiKVM uses a configured [WOL GPIO output](https://docs.pikvm.org/wol/); enter the output channel in the app if several exist. WOL requires support in the computer's firmware and network adapter, and sending a packet does not confirm the computer woke.
- The app PIN is stored as a salted PBKDF2 hash. Biometric checks use Android's system prompt. If a PIN-only lock is forgotten, clearing app data removes both the lock and saved KVMs.

## Protocol references

- [PiKVM HTTP API](https://docs.pikvm.org/api/)
- [PiKVM input WebSocket API](https://github.com/pikvm/kvmd/blob/master/kvmd/apps/kvmd/api/hid.py)
- [PiKVM video modes](https://docs.pikvm.org/video/)
- [NanoKVM Direct H.264 stream](https://github.com/sipeed/NanoKVM/blob/main/web/src/pages/desktop/screen/direct.worker.ts)
- [NanoKVM PiKVM bridge](https://github.com/Sergi095/nanokvm-pikvm-bridge)
