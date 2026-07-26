# Releasing BluebirdOps

## Versioning

Releases are tagged `vX.Y.Z`.

- **X (major)** tracks the upstream OpenNMS Horizon major this fork is based on. It is *not* a
  SemVer major — a release containing breaking changes stays on the same major as long as it is
  still built on the same Horizon line. Breaking changes are called out at the top of the release
  notes instead.
- **Y (minor)** is raised for feature work and for breaking changes within a Horizon line.
- **Z (patch)** is raised for fixes and dependency updates.

Commit messages follow Conventional Commits, so the commit log classifies the change set, but the
major is driven by the upstream base rather than by `!`/`BREAKING CHANGE` alone.

## Cutting a release

A release is triggered by **pushing a `vX.Y.Z` tag**. Nothing else starts one; there is no manual
release workflow dispatch.

### 1. Preflight

- On `main`, clean working tree, in sync with `origin/main`.
- The latest CI run for that exact commit is green.

### 2. Bump the version

`make release RELEASE_VERSION=X.Y.Z` exists, but its `setversion` macro has a known gap: the `sed`
that updates the non-POM files keys off the current `-SNAPSHOT` value, and because the previous
release's snapshot step never bumped them, **four files silently keep the previous release's
version**. Set them by hand.

Run the POM bump (three separate reactors):

```bash
./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false --file deploy/pom.xml
./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false --file e2e-tests/pom.xml
```

Then set these four to `X.Y.Z` by hand:

| File | Field |
|---|---|
| `pom.xml` | `<opennms.osgi.version>` |
| `core/web-assets/package.json` | `"version"` |
| `docs/antora.yml` | `full-display-version` |
| `opennms-full-assembly/src/test/java/org/opennms/assemblies/karaf/OnmsKarafTestCase.java` | `.version("…")` |

> `docs/antora.yml` also has a plain `version:` key. That is the Antora documentation-branch field
> and is bumped separately, out of band — **do not** change it during a release.

Verify with `./mvnw validate`, then commit as `release: BluebirdOps X.Y.Z` (signed off).

### 3. Tag and push

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin main
git push origin vX.Y.Z
```

Commits and tags are GPG-signed (`commit.gpgsign` / `tag.gpgsign`).

### 4. Publish the release notes

The `create-github-release` job attaches assets but does **not** write a release body, so it will
not overwrite notes that already exist. Create the release with curated notes right after pushing
the tag:

```bash
gh release create vX.Y.Z --repo bluebird-community/opennms --notes-file notes.md --latest
```

CI then adds the assets to that release.

### 5. Return main to a snapshot

Repeat the three `versions:set` calls with `X.Y.(Z+1)-SNAPSHOT` and commit as
`release: BluebirdOps X.Y.(Z+1)-SNAPSHOT`. The four files above stay at the release version — that
lag is expected and is what the next release's bump corrects.

## What the pipeline produces

On a `v*` tag, `.github/workflows/main.yml` builds and publishes:

- **Tarballs** — core, Minion and Sentinel `.tar.gz`
- **Packages** — `.deb` and `.rpm` for core, Minion and Sentinel
- **`SHA256SUMS`** plus its cosign signature (`.sig`) and certificate (`.pem`)
- **SBOM** — `sbom-vX.Y.Z.spdx.json` (syft, SPDX JSON)
- **Build provenance** — SLSA attestations for the artifacts and for each image digest
- **Container images** — pushed to `quay.io/bluebird`

Artifacts are attached to the GitHub Release; images land in Quay.

### Container tags

| Trigger | Repository | Tags |
|---|---|---|
| `vX.Y.Z` tag | `quay.io/bluebird/{core,minion,sentinel}` | `X.Y.Z` and `latest` |
| push to `main` | `quay.io/bluebird/{core,minion,sentinel}-snapshot` | short git SHA and `latest` |

Release images and snapshot images live in **separate repositories**, so a `main` build can never
move the `latest` tag of a released image.

## Verifying a release

### Artifacts

```bash
gh release download vX.Y.Z --repo bluebird-community/opennms -p 'SHA256SUMS*'

cosign verify-blob \
  --certificate SHA256SUMS.pem \
  --signature SHA256SUMS.sig \
  --certificate-identity-regexp '^https://github\.com/Bluebird-Community/opennms/\.github/workflows/main\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  SHA256SUMS

sha256sum -c SHA256SUMS   # against the downloaded artifacts
```

### Images

```bash
cosign verify \
  --certificate-identity-regexp '^https://github\.com/Bluebird-Community/opennms/\.github/workflows/main\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  quay.io/bluebird/core:X.Y.Z
```

### Provenance

```bash
gh attestation verify opennms-X.Y.Z.tar.gz --repo Bluebird-Community/opennms
```

Signing is keyless (Sigstore, GitHub OIDC) — there is no long-lived key to manage, and the
certificate identity above is what binds a signature to this repository's release workflow.

## If the pipeline changes

Update this file in the same pull request. A `RELEASING.md` that disagrees with
`.github/workflows/main.yml` is worse than none.
