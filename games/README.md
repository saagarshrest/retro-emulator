# Included games

Put Game Boy and Game Boy Color ROMs (`.gb`, `.gbc` or `.zip`) in this folder, then rebuild the app:

```sh
./gradlew assembleRelease
```

Every ROM in this folder is packaged into the APK and appears in the app's game list automatically, marked **Included**. Players don't need to import anything.

- **Only include games you have the right to distribute.** That means your own homebrew, or games whose license allows redistribution (many titles on [Homebrew Hub](https://hh.gbdev.io/) do). Commercial ROMs are copyrighted: don't commit them to this public repository or ship them in a published APK.
- Each ROM adds its size to the APK. Most Game Boy games are 32 KB–2 MB; the largest are 8 MB.
- New or changed ROMs are added the next time the updated app starts. If a player removes an included game, it stays removed until you change that ROM file.
- Unsupported cartridge types (MBC6, MBC7, HuC3, MMM01, TAMA5, Pocket Camera) are skipped.
