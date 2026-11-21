# Listopia Android
Native Kotlin + Jetpack Compose app for preparing Top 10, Character VS and Then vs Now video projects.

## Build
Requires JDK 17 and Android SDK 35.
```bash
gradle assembleDebug
gradle bundleRelease
```
APK: `app/build/outputs/apk/debug/app-debug.apk`
AAB: `app/build/outputs/bundle/release/app-release.aab`

## API keys
Enter Gemini and Pixabay keys in Settings. Keys are stored in Android encrypted preferences and are never part of the repository. Requests go directly from the device to the providers. Keep your keys private.

## Project storage
Generated projects are stored in the app-specific external files directory under `Listopia/Projects`. Use Android app storage settings or a future export feature to move files elsewhere.

## Current scope
The app generates structured research and metadata with Gemini, searches/downloads Pixabay images, and saves project text and images locally. Pixabay is not a guaranteed source for copyrighted characters or historical photographs; missing results are clearly shown for manual replacement.
