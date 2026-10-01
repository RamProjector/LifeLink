# Security Policy

LifeLink Cloud is an MVP deployment package for a medical-adjacent coordination helper. Do not commit real patient information, donor medical information, database passwords, API tokens, Firebase credentials, or private keys.

Keep `LIFELINK_AUTH_REQUIRED=true` in hosted environments. The hosted PostgreSQL adapter verifies Supabase JWT signatures and enforces ownership from verified claims. Application-level rate limits and append-only audit events are implemented, but their coverage and operation must still be verified in the deployed environment. Before production use, also use HTTPS, apply database least privilege, configure backups and retention, complete privacy and clinical-policy review, and distribute only a signed Android release.

To report a vulnerability, do not open a public issue containing sensitive details. Contact the repository owner privately through GitHub.

## Firebase / Google API key in `google-services.json`

`LifeLinkAndroid/app/google-services.json` is committed on purpose: the Firebase
Android config must ship inside the APK, and its `api_key` is a client
identifier, not a server secret. GitHub secret scanning flags it as a
`google_api_key`; both alerts are resolved as **false positive** for that reason.

That is only safe while the key is restricted. The key **must** be restricted in
the Google Cloud console (APIs & Services -> Credentials -> the Android key):

- **Application restriction:** Android apps, with the package name
  `com.lifelink.app` and the release signing certificate SHA-1 (add the debug
  SHA-1 too if debug builds need it).
- **API restriction:** limit it to the Firebase APIs the app actually uses
  (Firebase Installations, Firebase Cloud Messaging, Identity Toolkit) rather
  than "Don't restrict key".

An unrestricted key can be abused for quota and billing. If the key is ever
rotated, update `google-services.json` and re-verify the restrictions. Server
credentials (the FCM service-account JSON) are never committed; they are read
from the `FIREBASE_SERVICE_ACCOUNT_JSON` environment variable.

