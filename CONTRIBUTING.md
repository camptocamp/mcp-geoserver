# Contributing

Issues and pull requests are welcome.

- Bugs and ideas: open an [issue](https://github.com/camptocamp/mcp-geoserver/issues). For a bug, give the GeoServer version, the tool call and the result or error.
- Code: fork, branch, then open a pull request against `master`. Read `AGENTS.md` first for the layout and conventions. `./mvnw -q verify` must pass; CI runs it on every pull request.
- Security problems: see [SECURITY.md](SECURITY.md), not the issue tracker.

## Review rules

This repository follows the Camptocamp
[code review guidelines](https://github.com/camptocamp/c2c_iso_guidelines/blob/be4993252fbf266811a073697d44bf5480c14083/geospatial/code_review.md)
at that exact version.

Single-person repository: it has one maintainer, so pull requests are not reviewed by a second person.
Changes still go through a pull request, and CI (tests and the SonarQube quality gate) must pass before merging.

Dependabot pull requests are merged, or closed with a comment explaining why.
