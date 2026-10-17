# Security testing

CI runs `.github/workflows/security.yml` on every PR and on push to master, with three jobs:

- `dependency-review` (`actions/dependency-review-action`): PR-only, fails on a dependency vulnerability of `high` severity or above.
- `snyk` (`snyk/actions/maven-3-jdk-17`): `test --maven-aggregate-project --severity-threshold=high`. Gated on the `SNYK_TOKEN` secret (`if: env.SNYK_TOKEN != ''`), so it no-ops on Dependabot and fork PRs.
- `osv` (`google/osv-scanner-action`): builds a CycloneDX SBOM (`cyclonedx:makeAggregateBom`, provided and test scope excluded in pom.xml) and scans it against the OSV database (osv.dev). Needs no token and no CVE download, so it runs and gates even on Dependabot and fork PRs. Fails when any known vulnerability is found. Scope is the bundled/app deps only; the GeoServer/GeoTools/Spring stack is `provided` and shipped by the host, so it is deliberately out of scope here.

The SonarQube quality gate (`.github/workflows/sonarqube.yml`) also gates new code; see "Quality gate (SonarQube)" above. The release workflow (`.github/workflows/release.yml`) runs `snyk monitor` to post the dependency snapshot to Snyk, again gated on `SNYK_TOKEN`.

Secrets (`gh secret list -R camptocamp/mcp-geoserver` shows which are set):

- `SNYK_TOKEN`: Snyk test in CI and `snyk monitor` on release.
- `SONAR_TOKEN`: SonarQube scan.

Run locally:

- Snyk, if installed and authed: `snyk test --maven-aggregate-project --severity-threshold=high` (same as CI). Covers bundled and transitive deps.
- OSV (needs no token): `./mvnw -q -DskipTests cyclonedx:makeAggregateBom` then `osv-scanner scan source --lockfile=./target/bom.json`. Same scope as CI (app deps, provided excluded); non-zero exit means a vulnerability was found.
- `/security-review` skill reviews the pending branch changes; run it before merging security-relevant work.

Live assessment (hands-on test of a running server) house rules:

- Target only localhost (local GeoServer at `http://localhost:8080/geoserver`, MCP at `/mcp`). Never a shared or remote instance.
- Non-destructive: no delete or modify of real catalog objects.
- Confine any test objects to a scratch workspace and remove it after.
- Prove SSRF only against a local listener you control.
- Never run `gs_reload` on a shared instance.
