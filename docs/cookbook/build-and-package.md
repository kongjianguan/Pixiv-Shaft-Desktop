# Build and Package

English | [中文](build-and-package.zh.md)

Use this procedure to verify a local build and produce the macOS DMG.

## 1. Select JDK 21

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

Verify the selected runtime:

```bash
java -version
```

## 2. Compile and test

```bash
./gradlew :app:compileKotlin
./gradlew test
```

The observable result is a successful Kotlin compilation followed by a successful JUnit test run.

## 3. Run the application

```bash
./gradlew :app:run
```

The application should open a Compose Desktop window. A logged-out launch shows the Pixiv login screen; a valid Keychain access token opens the authenticated navigation shell.

## 4. Build the DMG

```bash
./gradlew :app:packageDmg
```

Verify that `app/build/compose/binaries/main/dmg/PixivShaft-1.0.0.dmg` exists. The package configuration includes `java.sql` and `jdk.unsupported` in the runtime image.

## 5. Check the package

Mount the DMG, copy `PixivShaft.app` to `/Applications`, and launch it from Finder. Test login, an API feed, an image detail page, and a small download before distributing the package.
