# Firebase Setup Guide for RoadFlow-AI

RoadFlow-AI initializes Firebase **manually from your `.env` file** (no committed
`google-services.json`). This keeps all credentials out of source control. Follow
these steps to connect your own Firebase project.

## Step 1: Create a Firebase Project

1. Go to the [Firebase Console](https://console.firebase.google.com/)
2. Click **"Create a project"** (or **"Add project"**)
3. Enter a project name (e.g. `RoadFlow-AI`)
4. Google Analytics is optional (not required)
5. Click **"Create project"** → **"Continue"**

## Step 2: Register the Android App

1. In the project dashboard, click the **Android icon** ("Add app")
2. Package name: `com.roadflow.ai`
3. App nickname: `RoadFlow-AI` (optional)
4. SHA-1 is not required for Realtime Database
5. Click **"Register app"**

## Step 3: Enable Realtime Database

1. Sidebar → **Build → Realtime Database**
2. Click **"Create Database"**
3. Choose a location (note it — e.g. `asia-southeast1`)
4. Start in **test mode** for development
5. Click **"Enable"**, then copy the database URL shown at the top
   (e.g. `https://your-project-default-rtdb.asia-southeast1.firebasedatabase.app/`)

> ⚠️ Test mode allows anyone to read/write. Secure it with
> [Realtime Database Rules](https://firebase.google.com/docs/database/security)
> before any real deployment.

## Step 4: Copy the config values into `.env`

Open **Project Settings** (⚙️ gear icon → *Project settings* → *General* tab →
*Your apps*). You'll find every value the app needs. Then:

```bash
cp android_app/.env.example android_app/.env
```

Fill in `android_app/.env` with your values:

| `.env` variable | Where to find it (Firebase Console) |
|---|---|
| `FIREBASE_API_KEY` | Project settings → General → *Web API Key* |
| `FIREBASE_APP_ID` | Project settings → Your apps → *App ID* (`1:...:android:...`) |
| `FIREBASE_PROJECT_ID` | Project settings → *Project ID* |
| `FIREBASE_PROJECT_NUMBER` | Project settings → *Project number* |
| `FIREBASE_STORAGE_BUCKET` | Project settings → *Storage bucket* |
| `FIREBASE_DB_URL` | The Realtime Database URL from Step 3 |

> 💡 Tip: if you'd rather, download `google-services.json` from the console and copy
> the same values out of it — the app does **not** read that file, it only needs the
> values in `.env`.

## Step 5: Build & Run

1. In Android Studio: **File → Sync Project with Gradle Files**
2. Build and run on a device/emulator
3. At launch, `RoadFlowApp` initializes Firebase from your `.env` values
   (see `android_app/app/src/main/java/com/roadflow/ai/RoadFlowApp.kt`)

## Verification

1. Open the app → **Report a Damage** → capture/upload a photo → **Submit Report**
2. In **Firebase Console → Realtime Database**, a new entry appears under `reports/`
3. **Login → Continue as Officer → Dashboard** shows that report on the map

If Firebase values are missing from `.env`, the app still builds and runs, but logs
a warning and skips cloud features (on-device scanning + RHI still work offline).
