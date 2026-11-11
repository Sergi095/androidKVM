# F-Droid preparation

The `fdroid` Gradle flavor is the source-built catalog variant. Build it without a
signing key:

```sh
./gradlew assembleFdroidRelease
```

It has the same KVM features and application ID as the `github` flavor. It omits
the GitHub APK updater, the APK-install permission, and the update FileProvider.
The `github` flavor keeps the existing updater and signed release workflow.

The source repository includes F-Droid listing text, an icon, and two screenshots
under `fastlane/metadata/android/en-US/`. The terminal asset sources and hashes
are recorded in [terminal/PROVENANCE.md](../app/src/main/assets/terminal/PROVENANCE.md).

The F-Droid build recipe uses the reproducible-build route to preserve app data
and allow in-place updates from the GitHub version. Both release APKs are built
with Java 21 to match F-Droid's build environment. F-Droid verifies that its
source build matches the signed reference APK before listing the app.

The [build recipe](../fdroid/metadata/org.androidkvm.app.yml) is submitted as
`metadata/org.androidkvm.app.yml` to `fdroid/fdroiddata` on GitLab.

The repository was created in November 2026. F-Droid's inclusion policy normally
expects three months of development unless there is evidence of users or another
listed exception. A maintainer decides whether an exception applies.
