# Firebase Setup Guide for RoadFlow AI

## Step 1: Create a Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click **"Create a project"** (or **"Add project"**)
3. Enter project name: `RoadFlow-AI`
4. Disable Google Analytics (optional, not needed)
5. Click **"Create project"** → wait → **"Continue"**

## Step 2: Register Your Android App

1. In the Firebase project dashboard, click the **Android icon** (or "Add app")
2. Enter the package name: `com.roadflow.ai`
3. App nickname: `RoadFlow AI` (optional)
4. Skip the SHA-1 fingerprint (not needed for Realtime Database)
5. Click **"Register app"**

## Step 3: Download `google-services.json`

1. Firebase will show a **"Download google-services.json"** button — click it
2. **Place the downloaded file in:**
   ```
   android_app/app/google-services.json
   ```
3. Click **"Next"** through the remaining steps in the Firebase wizard

## Step 4: Enable Realtime Database

1. In the Firebase Console sidebar, click **Build → Realtime Database**
2. Click **"Create Database"**
3. Choose a location (e.g., `us-central1` or closest to you)
4. Select **"Start in test mode"** (allows read/write for 30 days)
5. Click **"Enable"**

> ⚠️ **Test mode** allows anyone to read/write. This is fine for development but must be secured with rules before production.

## Step 5: Uncomment the Gradle Plugin

Open `android_app/app/build.gradle` and **uncomment line 5**:

```diff
 plugins {
     id 'com.android.application'
     id 'org.jetbrains.kotlin.android'
-    // TODO: Uncomment after placing google-services.json in app/
-    // id 'com.google.gms.google-services'
+    id 'com.google.gms.google-services'
 }
```

## Step 6: Sync & Build

1. In Android Studio: **File → Sync Project with Gradle Files**
2. Build the project — it should now compile without the `google-services.json` error
3. Run on device → Welcome → Report a Damage → submit → the report will appear in Firebase Console under **Realtime Database → reports/**

## Verification

After setup, you can verify Firebase works by:
1. Opening the app → **Report a Damage** → take/upload a photo
2. Going to **Firebase Console → Realtime Database**
3. You should see a new entry under `reports/` with your damage data
4. Then: **Login → Continue as Officer → Dashboard** should show the report on the map
