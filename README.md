# Umineko Installer

An Android app that installs [Umineko Project](https://umineko-project.org/en/downloads/) on your phone in one go.
You don't have to download every file yourself and put them together in a folder.

It does what you would otherwise do by hand:

1. It opens the official downloads page and finds every download link (resources 1-12, the resource update,
   scripts, game engine, language packs), in the order the page lists them.
2. It downloads each file. It uses the Yandex Disk mirror or a direct link. Mega isn't supported, but you can
   paste other links yourself.
3. It extracts each archive (`.zip` / `.7z`) into one game folder. Later files overwrite earlier ones,
   just as the instructions say. The resource files (`.001` to `.012`) are parts of one password-protected
   archive. The app waits until all of them are downloaded, then unpacks them together with the password
   (already filled in). You can delete each archive right after extracting it to save space.
4. It offers to install the downloaded ONScripter-RU engine APK.

The download runs in the background with a notification. If it stops (no internet, phone restarted), press the button
again and it continues where it stopped. Finished files and half-downloaded files are kept.

## Getting the app

Every push builds the APK on GitHub Actions. Open the **Releases** page of this repository on your phone,
download `UminekoInstaller.apk` and install it. You'll need to allow "Install unknown apps".

## Using it

1. **Allow access to files**. The game folder lives in your phone's main storage, so the app needs this.
2. Check the **game folder**. It must be the folder ONScripter-RU reads the game from. The default is `ONScripter-RU`
   in your main storage. If yours is different, type its path.
3. **Find the files on the page**, then untick anything you don't need, such as other languages or engines for
   other systems.
4. **Download & install everything**. Use Wi-Fi and keep the phone charging. It's over 10 GB.
5. **Install the game engine** when the button appears, then start the game from ONScripter-RU.

## Building it yourself

```
gradle assembleRelease   # needs JDK 17 and the Android SDK (platform 34)
```
