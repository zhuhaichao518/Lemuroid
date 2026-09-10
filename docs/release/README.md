# Bundled release audit — 2026-09-11

**License audit status: incomplete.** The publisher requested that the APK be
released before the outstanding audit work is complete. Publication does not
mean that the remaining source and licensing requirements have been satisfied.

The APK is `Lemuroid-1.17.0-cloud-stick-release.apk`, built from application
commit `6afca895760f9406d8839ff239b31f4ff3fd888a` and the unmodified binary
submodule `Swordfish90/LemuroidCores` at
`fee2e824525daa22bcf318f96127fe43fa8a15ad`.

## What has been established

- Lemuroid has GNU GPLv3 in `COPYING`; existing copyright and license notices
  are retained. This is an unofficial fork, not an upstream release.
- The CloudPlayPlus author granted permission for this GPLv3 fork. See
  `cloudplayplus-permission.md`; this does not change the upstream license.
- `retro_get_system_info` was called on all 20 installed arm64 cores without
  initializing an emulator or loading a game. Reported versions are recorded
  in `core-provenance.json`.
- Sixteen cores report a source revision that resolves to a full public commit.
  This alone does not prove there were no additional build patches.
- The binary audit script compares every packaged ABI against the pinned
  submodule, records SHA-256 digests and checks for the observed version string.

## Remaining provenance issues

| Core | Observed version | Missing evidence |
| --- | --- | --- |
| Citra | `5263fae33-dirty` | Build-time changes are not captured by the named source commit. Obtain the actual changes or rebuild from known source. |
| DOSBox-pure | `1.0-preview3` | The matching release tag exists, but the binary supplies no exact revision. Confirm the build revision. |
| melonDS DS | `1.2.0` | The matching `v1.2.0` tag exists, but the version alone does not establish the binary's exact revision. |
| Beetle WonderSwan | `v0.9.35.1` | No source commit is reported. A matching version label does not identify a unique source tree. |

The upstream core update commit is `1194b7dcc6208f8dd62b55b66753fde7838dbc91`
(2025-11-18, “Update cores (included support for 16k pages).”). Its binary
repository and `update_cores.ipy` are not substitutes for corresponding core
source and any build patches. The update script downloads moving “nightly/latest”
builds, so repeating it now would not recover the historical build inputs.

## License and source collection

```sh
python3 scripts/audit_core_release.py \
  --apk /path/to/Lemuroid-1.17.0-cloud-stick-release.apk \
  --output /path/to/release-materials \
  --download-sources
```

This collects pinned source archives and verbatim license/notice files, without
extracting executable files. Candidate source tags are explicitly labeled.
Archives with Git submodules require the submodules' pinned sources too; GitHub's
automatic source ZIPs do not include those contents. Failed downloads and
unresolved versions remain explicit errors, not approval.

The final release must include applicable license texts for the bundled cores
and their dependencies, plus the corresponding source/build materials required
by each license. FBNeo and other cores have their own terms, including
noncommercial restrictions; do not label all bundled components GPLv3 or assume
this package may be sold or used for fundraising. FBNeo's exact applicable
notice is in its pinned source tree at `src/license.txt`.

Do not bundle the user's game ROMs, BIOS files, saves or private signing keys.
This audit does not constitute a legal opinion or a complete trademark/patent
review.

## Application build

Use JDK 17, Android SDK platform 35/build-tools 34.0.0, and the repository's
Gradle 8.10.2 wrapper. Initialize pinned submodules, then run:

```sh
git submodule update --init --recursive
./gradlew :lemuroid-app:assembleFreeBundleRelease
```

For the existing signing configuration, create your own `release.jks` with alias
`lemuroid`, or change the signing configuration to your own private keystore.
The distributor's private key is not provided. A different key cannot update
the installed APK in place; back up application data before replacing it.

This local build used Maven mirrors at `maven.aliyun.com/repository/`
(`google`, `central`, `gradle-plugin`, and `public`) in addition to the existing
repositories. It used a locally downloaded Gradle 8.10.2 distribution rather
than changing the Gradle version. A local debug-app-name override does not affect
`freeBundleRelease`. These environment-specific edits remain outside the source
commit; a portable checkout should use the official Gradle distribution URL.

Before publishing, finish auditing native bridge/dependency sources as well as
core sources. In particular, the application pins LibretroDroid `0.13.2` and
PadKit `1.0.0-beta1`; their source and notices must be covered as applicable.
