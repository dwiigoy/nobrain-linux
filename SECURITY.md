# Security Policy

## Supported Version

Only the latest published pre-release is currently supported.

## Reporting

Use GitHub's private security advisory feature for vulnerabilities that should
not be public before a fix exists. For non-sensitive hardening suggestions, use
a regular GitHub issue. Do not place passwords, private SSH keys, signing keys,
tokens, or personal data in an issue.

## Device Model

NoBrain currently assumes one trusted owner per Android installation. The
`nobrain` account has passwordless sudo, and the fresh-install passwords are
documented defaults. Change both passwords after installation. SSH remains
key-only until the owner explicitly selects another mode.
