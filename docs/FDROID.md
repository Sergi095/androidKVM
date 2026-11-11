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

Before requesting inclusion in the official F-Droid catalog:

1. Tag a release that includes the F-Droid flavor and its version code.
2. Test the [draft build recipe](../fdroid/metadata/org.androidkvm.app.yml)
   with `fdroidserver`, then submit it as `metadata/org.androidkvm.app.yml`
   to `fdroid/fdroiddata` on GitLab.
3. Resolve signing continuity. A default F-Droid-signed APK cannot replace the
   GitHub-signed APK with the same application ID. For in-place updates, publish
   a separately built, developer-signed `fdroid` APK and prove its unsigned
   contents match F-Droid's rebuild using `Binaries` and
   `AllowedAPKSigningKeys`. A local signed-versus-unsigned rebuild comparison
   passed with `apksigcopier`; F-Droid's independent build still needs to pass.
   Otherwise, users must reinstall and re-add their saved KVMs.

The repository was created in November 2026. F-Droid's inclusion policy normally
expects three months of development unless there is evidence of users or another
listed exception. A maintainer decides whether an exception applies.
