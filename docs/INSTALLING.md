# Installing and updating on your own phone

This app is not on the Play Store and is not meant to be. It is side-loaded: you build the
APK yourself and install it over USB. That works indefinitely and costs nothing, but it
puts one responsibility on you that Play would otherwise handle - keeping the signing key.
Read [The signing key](#the-signing-key) before you start following channels for real.

Commands are given for PowerShell on Windows. On macOS or Linux use `./gradlew` and forward
slashes; nothing else differs.

## One-time phone setup

1. Settings > About phone > tap **Build number** seven times.
2. Back out to Settings > System > **Developer options** > enable **USB debugging**.
3. Connect the phone to the computer. Use a cable that carries data; charge-only cables are
   the usual reason a phone never appears.
4. The phone shows **Allow USB debugging?**. Tick "Always allow from this computer" and
   accept. If no prompt appears, open the USB notification in the shade and set the mode to
   **File transfer**, which usually triggers it.

Confirm the computer can see it:

```powershell
adb devices
```

The phone should be listed as `device`. `unauthorized` means step 4 has not been accepted
yet. An empty list means the cable, the port, or USB debugging.

`adb` lives in `platform-tools` inside the Android SDK. If it is not on your `PATH`, call it
by full path or add that directory to `PATH`.

## First install

Install the **release** build on the phone you actually use. It is signed with a durable key
you control, so it can be updated for as long as you keep that key. A debug build is signed
with a throwaway machine-local key and is for development only - see
[The signing key](#the-signing-key) for why that distinction is the whole ballgame.

Set up signing once per machine ([details below](#configuring-signing-on-a-machine)), then:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat installRelease
```

To build and install separately - useful when the phone is not attached at build time:

```powershell
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

If the output is named `app-release-unsigned.apk`, signing is not configured on this machine
and the APK cannot be installed. Fix the configuration rather than falling back to a debug
build, which would install fine and then be unupdatable.

For development on an emulator, `installDebug` is still the right command. The debug build
installs as `com.miniyoutube.app.debug`, a separate app with its own database, so it never
touches the release install.

Without a cable, copy `app-debug.apk` to the phone by any means and tap it in the Files app.
Android will ask you to allow "install unknown apps" for whichever app you opened it from,
and will warn that the source is unverified. Both are normal for anything not from Play.

## Updating

Rebuild and install again. The same command does it:

```powershell
.\gradlew.bat installRelease
```

This is an in-place upgrade, not a fresh install: **your channels and backlog are preserved.**
No uninstall, no re-following channels.

An update succeeds when both of these match the installed app:

- the `applicationId` (`com.miniyoutube.app`), and
- the **signing key**.

Nothing else matters. `versionCode` does not need bumping for `adb install -r` or
`installRelease`; Android only refuses to move *backwards* to a lower `versionCode`.

If either the id or the key differs, Android treats the APK as a different app and refuses
the update. The only way in from there is to uninstall, and **uninstalling deletes the
database**. This is why the key matters more than anything else in this document.

## The signing key

Android identifies an app by `applicationId` **plus signing certificate**. An update must be
signed by the same key as the install it replaces - forever, with no override, no recovery
path, and no support channel. Lose the key and the installed app can never be updated again;
you can only uninstall and start over with no channels and an empty backlog.

A debug build is signed with an automatically generated, machine-local keystore at
`~/.android/debug.keystore`. That key is an accident of tooling: it appears without being
asked for, lives outside every backup, and is regenerated silently if it goes missing. It is
fine for an emulator and wrong for the phone you actually use.

Release builds are therefore signed with a **dedicated key you own**, generated once:

```powershell
keytool -genkeypair -v -keystore "$env:USERPROFILE\.android\miniyoutube-release.jks" `
  -alias miniyoutube -keyalg RSA -keysize 4096 -validity 10950 `
  -dname "CN=Mini YouTube, OU=Personal, O=Mini YouTube, C=PT"
```

`keytool` ships with the JDK and is often not on `PATH`. If it is not found, call it as
`"$env:JAVA_HOME\bin\keytool.exe"`, or from the JDK bundled with Android Studio at
`jbr\bin\keytool.exe` inside the Android Studio install directory.

The keystore lives **outside the repository** deliberately. A gitignored file inside the tree
is one `git add -f` away from being published, and this repository is public. `.gitignore`
covers `*.jks`, `*.keystore`, `keystore.properties`, `signing.properties` and
`secrets.properties` with no negated exceptions, but defence in depth is cheap and the
failure is permanent.

### Back it up now

The key file and its password are the only unrecoverable artifacts in this project.
Everything else can be rebuilt from source; this cannot. Put both somewhere durable - a
password manager entry with the `.jks` attached is ideal.

Reinstalling Windows, wiping the user profile, or moving to a new machine without that
backup ends the same way: the installed app can never be updated again, and recovering means
an uninstall that destroys the database.

Note that the database is *not* protected by backing up the key, and the key is not protected
by Android's backup of the database. They are separate risks and both need covering.

### Configuring signing on a machine

Signing credentials are read from `keystore.properties` in the repository root. The file is
gitignored and must be recreated on each machine:

```properties
# Forward slashes: java.util.Properties treats a backslash as an escape character.
storeFile=C:/Users/<you>/.android/miniyoutube-release.jks
storePassword=<password>
keyAlias=miniyoutube
keyPassword=<password>
```

When the file is absent - on CI, or on a fresh clone - no release signing config is declared
and `assembleRelease` produces `app-release-unsigned.apk`. That is deliberate. Falling back
to the debug key would produce an APK that installs perfectly and then cannot be updated by
a real release, which is a far worse failure than an obviously unusable artifact.

Verify which key actually signed an APK:

```powershell
apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk
```

`apksigner` is in `$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\`.

## Verifying an install worked

```powershell
adb shell pidof com.miniyoutube.app     # non-empty means it is running
adb logcat -d -b crash -t 200           # empty means it did not crash
```

To watch a crash as it happens, clear the buffer first with `adb logcat -c`, reproduce, then
read it back.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `adb devices` lists nothing | Charge-only cable, USB debugging off, or a flaky port. Try another cable first. |
| Device shows as `unauthorized` | The debugging prompt has not been accepted. Unplug, replug, watch the phone screen. |
| Prompt never appears | Set the USB mode to **File transfer** from the notification shade. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app is signed with a different key. See [The signing key](#the-signing-key). Uninstalling fixes it but destroys the database. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The APK has a lower `versionCode` than what is installed. Bump `versionCode` in `app/build.gradle.kts`. |
| `INSTALL_FAILED_USER_RESTRICTED` | Some OEM skins, Xiaomi's MIUI in particular, require "Install via USB" to be enabled in Developer options. Stock Android and Samsung One UI do not. |
| App installs but instantly closes | Check `adb logcat -b crash`. A `minSdk` mismatch is not possible here (26), so it is a real crash worth reporting. |

## What happens to your data

The database is separate from the APK. It survives updates and is removed only by
uninstalling or by clearing app storage. Android's backup also copies it to your Google
account; see [Your data](../README.md#your-data) for exactly what is included and how to
turn it off.

Backup is a genuine safety net for a lost phone, but it is not instant and not a substitute
for keeping the signing key. Restoring a backup onto an app you can no longer update leaves
you exactly where you started.

### Copying the database off a debug build

Useful before anything destructive, and the only way to inspect real data directly. This
works on **debug builds only**: `run-as` requires a debuggable package, so a release install
is closed to it and there is no way in without root.

```bash
adb exec-out "run-as com.miniyoutube.app.debug cat databases/mini_youtube.db" > mini_youtube.db
```

Use `exec-out`, never `adb shell`. `adb shell` allocates a PTY that translates LF to CRLF,
which silently corrupts binary data - the copy comes back slightly larger than the original
and SQLite then reports the tables as missing rather than failing outright. Comparing sizes
against `adb shell run-as com.miniyoutube.app.debug ls -l databases/` catches it immediately.

The database uses a TRUNCATE journal rather than WAL, so the one file is the whole database;
there is no `-wal` sidecar holding recent writes.
