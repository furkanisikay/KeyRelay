# Security Policy

## Reporting a vulnerability

Please do not open a public issue. Report vulnerabilities privately through GitHub's
**Private vulnerability reporting**: open the repository's **Security** tab and choose **Report a vulnerability**.

## Scope

The KeyRelay Windows service injects keystrokes received over the network, including on the lock screen.
Any way to bypass authentication (pairing, device token, certificate pinning) and inject keys is considered **critical**.
