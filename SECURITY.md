# Security policy

## Reporting a vulnerability

Do not open a public issue for a security problem.

Email **vulnerability@camptocamp.com** with:

- the affected version or commit;
- the tool call or request that triggers the problem;
- the impact you observed or expect.

You will get an acknowledgement, and the report follows the Camptocamp
[vulnerability and incident management process](https://github.com/camptocamp/c2c_iso_guidelines/blob/be4993252fbf266811a073697d44bf5480c14083/geospatial/vulnerability_incident_management_process.md).

## Supported versions

Only the latest release receives security fixes.

## Automated checks

- Dependabot: weekly updates for Maven, GitHub Actions and the Docker base image, plus security alerts.
- SonarQube (`sast.camptocamp.net`): static analysis and quality gate on every pull request.
- Dependency review: pull requests adding a dependency with a known high or critical vulnerability fail.
- Snyk: dependency test on every pull request and on `master` (high and critical fail), monitoring of every tagged release.
