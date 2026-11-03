# Blurrer for Android

Take a photo or choose photos from your gallery, and Blurrer finds every face and blurs it. Everything runs on the phone: the app has no internet permission, and face detection uses Google ML Kit's on-device model, which is bundled inside the app.

## Features
- **Take photo** with the camera, **Choose photos** from the gallery (several at once), or share photos into Blurrer from any app.
- Faces are found automatically, including small faces in group photos (the photo is also scanned in tiles).
- **Tap** a blurred face to unblur it; **drag** a box over any face that was missed.
- Styles: Blur, Pixelate or Solid. Five strength levels. Three detection levels.
- **Save** to `Pictures/Blurrer` (no location or camera data is kept), **Save all**, or **Share**.

## Install
Every push to `main` builds the app with GitHub Actions. Open the latest release on your phone, download `Blurrer.apk`, and open it. Android will ask you to allow installs from your browser or file manager the first time.

Requires Android 10 or newer.

## Build locally
Open the folder in Android Studio, or run `./gradlew assembleRelease`.

The release build is signed with `app/blurrer.keystore` (password `blurrer`) so each build installs as an update over the previous one. It's a personal signing key; replace it before publishing to the Play Store.
