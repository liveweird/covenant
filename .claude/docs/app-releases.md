# Application releases and tags

`web/src/changelog/version.ts` is the source of the application version. A release changes
`APP_VERSION` and adds the matching English and Polish entry to
`web/src/changelog/entries.ts` in the same commit. Gradle and npm package versions are separate.
This process concerns Covenant's own releases; contract versions and major release lines in
the catalog are unrelated.

## Publishing a new version

1. Merge the reviewed version/changelog change into `main` after its required checks pass.
   Record the full release commit SHA and verify that commit's `APP_VERSION` and newest
   changelog entry match the intended version. Wait for CI on that exact main commit.
2. Create an annotated `v<APP_VERSION>` tag at that explicit SHA, then push that tag only.
   Never let a moving branch name choose the release commit. Existing published tags are
   immutable by convention: do not move, replace or force-push them.
3. Create the GitHub release using `gh release create` with `--verify-tag`. Supply a reviewed
   notes file containing that version's English body, a separator, and its Polish body from
   the tagged changelog. Do not generate product notes from commit messages. Mark only the
   highest stable version as Latest; use `--latest=false` for older releases.
4. Read back the remote tag's peeled commit, release body, draft/prerelease status and Latest
   selection. A local tag or a draft release alone does not complete publication.
5. When deployment is in scope, build from the recorded commit using the
   [run-stack playbook](../skills/run-stack/SKILL.md), verify the displayed version and SHA,
   and preserve existing data. Publishing a GitHub release does not itself deploy containers.

Documentation and test-only changes may retain the app version. They do not create another
release with the same version or move its tag; the displayed commit identifies the newer build.
The existing CI workflows check source/build behavior but do not publish GitHub releases.
Release/tag verification is part of the publishing agent's completion checklist.

## Historical backfill

The 2026-09-22 reconciliation covers the sixteen missing versions from 0.8.1 through 1.0.3.
Use the first `main` commit introducing each version as the historical target, and verify its
version/changelog snapshot. Existing v0.6.0, v0.6.1, v0.7.0 and v0.8.0 tags/releases stay intact.
Versions before 0.6.0 predate this reconciliation's release-history scope.

Backfilled release notes retain the original changelog date and explicitly identify the later
backfill date. GitHub's publication timestamp reflects actual publication; do not manufacture
an earlier publication date or imply newly rerun historical tests. Only v1.0.3 is marked Latest.
Later test-only fixes are available on `main` without changing the historical v1.0.3 tag.
