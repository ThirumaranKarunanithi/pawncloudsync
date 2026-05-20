# pawnbroking-android

Kotlin + Compose + Hilt + Retrofit + Firebase Cloud Messaging.

## Setup

1. Open the folder in **Android Studio Iguana (or newer)**. Let Gradle sync.
2. Create a Firebase project at https://console.firebase.google.com.
   - Add an Android app with package id `com.magizhchi.mobile`.
   - Download `google-services.json` and drop it into `app/`.
3. In Firebase project settings → Service accounts → Generate new private key.
   - Put it on your **cloud-api** host and point `GOOGLE_APPLICATION_CREDENTIALS` at it.
4. Run on emulator. `BuildConfig.API_BASE` is `http://10.0.2.2:8080` in debug,
   so it hits the cloud-api running on your laptop's docker-compose.

## End-to-end manual test

1. Start cloud-api (`docker compose up` in `pawnbroking-cloud-api`).
2. Install the outbox + run the sync-agent on a shop machine (or POST a fake
   event via curl — see cloud-api README).
3. Login on the app as `admin / admin` for shop `alwarpuram`.
4. Touch a table on the desktop app → the phone should buzz within ~5s.
5. Tapping the notification deep-links to the row detail.
