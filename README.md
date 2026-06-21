# Dumb Down Launcher

This repository hosts the **release and beta APKs** for Dumb Down Launcher and
serves the in-app update checker. The download URLs and update endpoint are
served from here, so this location is kept stable.

Active development happens in a separate **private** repository. Release and
beta builds are published here automatically by CI, which keeps the existing
download URLs and the in-app update checker working unchanged.

- Stable releases: https://github.com/Offline-DC/dumb-down-launcher/releases/latest
- Beta builds: published as pre-releases (opt in via long-press "updates" in All Apps)

## Releasing

Builds are produced and published from the private source repository. Pushing a
version tag there triggers CI, which builds the signed APK and publishes a
GitHub Release here:

- Stable: tag `vX.Y.Z`
- Beta / RC / Alpha: tag `vX.Y.Z-beta.N` / `-rc.N` / `-alpha.N`

Bump `versionCode` and `versionName` before tagging. Keep `versionCode`
monotonically increasing across both channels so the highest-code-wins update
logic behaves correctly.

## License

Proprietary — Copyright (c) 2026 Offline DC Inc. All rights reserved.
This source code is confidential and may not be used, copied, modified, or
distributed without prior written permission. See [LICENSE](LICENSE) for the
full terms.
