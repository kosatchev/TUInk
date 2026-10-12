# Security Policy

## Supported code

Security fixes target the latest code on `main` and the latest published
release. Experimental branches do not carry compatibility or
production-support guarantees, but security reports about them are welcome.

## Reporting a vulnerability

Please report vulnerabilities through
[GitHub private vulnerability reporting](https://github.com/kosatchev/tuink/security/advisories/new).
If that channel is unavailable, email the maintainer at
`kosatchev@yandex.com`. Do not open a public issue before a report has been
assessed and a coordinated disclosure is possible.

Include the affected component and version or commit, operating system and
terminal, reproduction steps, security impact, and any relevant logs. Remove
credentials, tokens, private URLs, document contents, and other sensitive data
from the report. Use only systems and endpoints you are authorized to test.

Relevant reports include terminal-control injection or unsafe terminal
restoration, path/symlink or installer ownership issues, unintended file
disclosure or modification, and compromised dependency or release integrity.

The maintainer will assess the report privately, request details if needed,
prepare a fix, and coordinate disclosure according to severity and available
release channels.
