# Selected AndroidX Material Icons

Copyright 2020 The Android Open Source Project. Licensed under the
[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
Every copied file retains its original license header and byte content.
The APK includes the full Apache license and attribution notice in
`assets/third_party/material-icons-LICENSE.txt` and
`assets/third_party/material-icons-NOTICE.txt`.

These are the original AndroidX 1.7.6 extended icon vector definitions used by
MangaLens. Core icons remain supplied by the matching Compose dependency.
The complete extended artifact contains 11,105 classes; using the selected
sources avoids packaging and verifying unused icon definitions.

`manifest.json` records the pinned Google Maven archive and every copied hash.
To add an icon, download the manifest's source URL, locate the matching 1.7.6
material-icons-core Android AAR, then run:

```sh
python3 scripts/android/update-material-icons.py --source-jar /path/to/sources.jar --core-aar /path/to/material-icons-core-release.aar
```

The updater rejects altered upstream archives and locally edited owned files.
Run the app compile and APK verification after updating. This is a packaging
change; install/runtime performance must be measured separately.
