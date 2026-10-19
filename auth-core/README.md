# auth-core

The shared login check used by every app in the fleet. A central login service sets a signed
cookie (`user:expiry:HMAC-SHA256`, base64url) on the parent domain; this library's filter
verifies it with `SSO_SIGNING_KEY` and sends visitors without a valid one to the login page.
Vendored here as a plain folder so this app builds on its own.
