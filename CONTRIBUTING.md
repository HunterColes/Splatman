# Contributing

Pull requests are welcome! You can look at [issues](https://github.com/HunterColes/Splatman/issues) for contribution opportunities. For other changes, please open an issue first to discuss what you would like to change.

## How to Contribute

1. **Fork the repository**
2. **Create a feature branch** from `master`
3. **Make your changes** - see [README.md](README.md) for build instructions
4. **Test your changes** - make sure all tests pass
5. **Open a Pull Request** with a clear description

## Development Setup

See the [Build & Installation](README.md#build--installation) section in the README for:
- Prerequisites (Android Studio, Java 17)
- Build commands
- Testing procedures

## Guidelines

- Follow existing Kotlin conventions (KtLint and Detekt enabled)
- Keep commits focused and write clear PR descriptions  
- Add tests for new functionality when appropriate
- For large changes, open an issue first to discuss the approach

## Dependency verification

Gradle verifies the sha256 of every dependency and plugin against `gradle/verification-metadata.xml`, so an artifact
whose checksum is not listed there fails the build with "Dependency verification failed". After adding or bumping a
dependency (or a plugin, or the Android Gradle Plugin), regenerate the file under the gate's lock, with every task the
gate and the other workflows resolve, and commit it with the bump:

```bash
tools/check.sh --write-verification-metadata sha256 assembleDebug testDebugUnitTest lintDebug detekt assembleRelease assembleDebugAndroidTest :splat-core:pitest
```

Review the diff: only the artifacts you meant to change should appear. Checksums only (no PGP keyring).
