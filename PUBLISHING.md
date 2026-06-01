# Publishing Earshot

Earshot ships two ways:

- **Android / JVM**: `dev.eknuth:earshot` on **Maven Central**.
- **iOS**: an `Earshot.xcframework` consumed via **Swift Package Manager**, served from each GitHub release.

Coordinate: `dev.eknuth:earshot:<version>`. The namespace `dev.eknuth` is tied to the domain
`eknuth.dev`, which you own. That is what makes the Maven Central namespace claim verifiable.

> Maven Central is **one-way**. A version that is released can never be overwritten or removed.
> Do the dry-run checks below, then publish deliberately. The first release is done by hand
> (this file); after that, pushing a `v*` tag lets CI do it.

---

## One-time setup

### 1. Claim the `dev.eknuth` namespace

1. Sign in at <https://central.sonatype.com> (GitHub login is fine).
2. **Account → Namespaces → Add Namespace** → enter `dev.eknuth`.
3. The Portal shows a **TXT record** to add to DNS for `eknuth.dev`. Add it at your registrar:
   - Host/name: `eknuth.dev` (or `@`)
   - Type: `TXT`
   - Value: the verification string the Portal gives you
4. Back in the Portal, click **Verify**. Once it flips to verified you can publish under `dev.eknuth.*`.

### 2. Generate a Central Portal user token

In the Portal: **Account → Generate User Token**. You get a *username* and *password* pair
(these are not your login). You will pass them to Gradle as `mavenCentralUsername` /
`mavenCentralPassword`.

### 3. Create a signing key (GPG)

Maven Central requires every artifact to be GPG-signed. This key has **no passphrase**
(`signingInMemoryKeyPassword` stays empty); security rests on protecting the exported key file
and the GitHub secret, which is where a passphrase would have to live anyway.

```bash
# Generate a passphrase-less RSA 4096 key under your identity.
gpg --batch --gen-key <<'EOF'
%no-protection
Key-Type: RSA
Key-Length: 4096
Subkey-Type: RSA
Subkey-Length: 4096
Name-Real: Edwin Knuth
Name-Email: eknuth@gmail.com
Expire-Date: 0
%commit
EOF

# Find the long key id (the hex after rsa4096/ ):
gpg --list-secret-keys --keyid-format=long

# Publish the PUBLIC key so Sonatype can verify signatures. RUN THIS FROM A MACHINE WITH OPEN
# NETWORK — Sonatype validates against keyserver.ubuntu.com at publish time.
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <LONG_KEY_ID>

# Export the PRIVATE key (ASCII-armored) for in-memory signing (gitignored as *.asc):
gpg --armor --export-secret-keys <LONG_KEY_ID> > earshot-signing-key.asc
```

Keep `earshot-signing-key.asc` secret. Never commit it (`.gitignore` already excludes `*.asc`).

### 4. Point Gradle at the credentials (local)

Credentials are wired through a git-ignored `.env` that you source before publishing (it sets
the `ORG_GRADLE_PROJECT_*` vars the plugin reads):

```bash
cp .env.example .env     # then edit in your Central Portal token username + password
source .env
```

The signing key loads from `earshot-signing-key.asc` automatically and has no passphrase, so
only the two token values need filling in. (If you prefer, put the same values in
`~/.gradle/gradle.properties` instead, just not in this repo.)

---

## Cutting a release

Versions come from `-Pearshot.version`; it defaults to `0.1.0`. The steps below use `0.1.0`;
substitute the real version for later releases.

### Step 1: build, checksum, and pin the XCFramework

```bash
# Android AAR + Kotlin metadata are built during publish; here we build the iOS XCFramework.
./gradlew :earshot:assembleEarshotReleaseXCFramework -Pearshot.version=0.1.0

# Zip it deterministically and compute the SPM checksum (run from the repo root).
ditto -c -k --sequesterRsrc --keepParent \
  earshot/build/XCFrameworks/release/Earshot.xcframework \
  Earshot.xcframework.zip
swift package compute-checksum Earshot.xcframework.zip
```

Copy the printed checksum into **`Package.swift`**:

- `url`: `https://github.com/eknuth/earshot/releases/download/v0.1.0/Earshot.xcframework.zip`
- `checksum`: the value just printed

> The SPM tag must contain the matching checksum, so commit `Package.swift` **before** tagging.

```bash
git add Package.swift
git commit -m "Release v0.1.0: pin XCFramework checksum"
```

### Step 2: dry-run the Maven publish

```bash
# Builds, signs, and stages everything locally without uploading. Fix any signing/POM error here.
./gradlew :earshot:publishToMavenLocal -Pearshot.version=0.1.0
```

### Step 3: publish to Maven Central

```bash
./gradlew :earshot:publishToMavenCentral -Pearshot.version=0.1.0 --no-configuration-cache
```

This uploads a **staged deployment** (the build sets `automaticRelease = false`). It does **not**
go live yet. Open <https://central.sonatype.com> → **Deployments**, confirm it validated, then
click **Publish** to make `dev.eknuth:earshot:0.1.0` permanent. (Sync to the public
`repo1.maven.org` mirror takes ~15–30 min.)

### Step 4: tag and attach the iOS binary

```bash
git tag v0.1.0
git push origin main --tags
```

Create the GitHub release for `v0.1.0` and upload `Earshot.xcframework.zip` to it, so the URL in
`Package.swift` resolves:

```bash
gh release create v0.1.0 Earshot.xcframework.zip --generate-notes
```

### Step 5: verify both ecosystems

```bash
# Maven (after the mirror sync):
#   implementation("dev.eknuth:earshot:0.1.0")

# SPM: in a throwaway app, add the package and confirm it resolves + the checksum matches:
#   https://github.com/eknuth/earshot  →  Up to Next Major  →  0.1.0
```

---

## Subsequent releases

Every release is cut by hand with the same five steps above, just with the new version number.
This is deliberate: Maven Central is irreversible, and the XCFramework zip on the GitHub release
must be the exact bytes whose checksum is pinned in `Package.swift` (a CI rebuild can differ
byte-for-byte and break SPM validation), so the locally built zip is always the source of truth.

The `Release` workflow (`.github/workflows/release.yml`) is a **manual** `workflow_dispatch`
convenience: trigger it from the Actions tab with a version input to rebuild the AAR + XCFramework
and (re)create the GitHub release. It does **not** publish to Maven Central and its verify step
fails loudly if a rebuild's checksum drifts from `Package.swift`. Maven Central publishing is
always the manual `source .env && ./gradlew :earshot:publishToMavenCentral` step, followed by the
**Publish** click in the Portal.
