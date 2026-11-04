# Build the APK from your phone with GitHub Actions

No PC and no Android Studio needed. Your phone uploads the code,
GitHub's servers compile it, and you download the finished APK.

## What is already prepared

- `.github/workflows/android.yml` — the build workflow. It runs on
  every push to `main`/`master`, and you can also run it by hand.
- It uses JDK 17 and Gradle 8.7 (the version in
  `android/gradle/wrapper/gradle-wrapper.properties`).
- It builds the debug APK and uploads it as an artifact called
  `warden-debug-apk`.
- This project does **not** use the Gradle Wrapper, so the workflow
  runs `gradle assembleDebug`, not `./gradlew assembleDebug`. Do not
  add a fake `gradlew` file — it would break the build.

## Steps, all from your phone

1. **Unzip first.** Unzip `Warden-github-ready.zip` on your phone
   (Files app, or ZArchiver from Google Play). You need the folders,
   not the zip itself, in the repo.
2. **Create a repo.** In Chrome on your phone, go to github.com/new
   (sign in, or create a free account first). Name it `warden`,
   leave it public or private — either works — and do **not** tick
   "Add a README", or you will get a merge conflict.
3. **Upload the files.** On the new repo page, tap **Add file >
   Upload files**, then upload the contents of the unzipped folder,
   keeping this exact structure at the repo root:
   ```
   README.md
   GITHUB_BUILD.md
   android/
   .github/workflows/android.yml
   ```
   Important: `android/` must stay a subfolder, and `.github/` must
   stay at the root. If you upload only the inside of `android/`,
   the workflow paths will not match and the build will fail.
   Uploading a whole folder from a phone browser can be fiddly: if
   it only lets you pick files, upload in batches, folder by folder,
   and check the file list afterwards. The workflow file is hidden
   (its folder starts with a dot) — make sure `.github/workflows/
   android.yml` is really there before you continue.
4. **Wait for the build.** Tap the **Actions** tab. You should see
   a run called "Build Android APK". The first build takes a few
   minutes while it downloads the SDK and libraries. Green check =
   success, red X = failed (tap it, then the failed step, and send
   me the error text).
5. **Download the APK.** Tap the successful run, scroll to
   **Artifacts**, tap `warden-debug-apk` to download it. It comes
   down as a zip — unzip it with ZArchiver or Files.
6. **Install it.** Tap the `app-debug.apk` inside, allow **Install
   unknown apps** for the app opening it if asked, then **Install**.

## If the build fails

The most common causes, in order:
- `.github/workflows/android.yml` is missing or in the wrong place.
- The `android/` folder was not uploaded as a subfolder.
- A file was skipped during upload. Compare the repo file list
  with the zip file list.

## Notes

- This builds a **debug** APK, signed automatically for testing on
  your own phone. It is not a Play Store release build.
- The v0.1 limit still applies: packet forwarding is not finished,
  so with Protection ON, apps will appear offline until you turn
  it OFF. See README.md. Test on a spare device first.
- Artifacts on GitHub stay available for 90 days by default, then
  expire — download the APK and keep a copy in Drive if you need it
  longer.
