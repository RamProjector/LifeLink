# Security Policy

LifeLink Cloud is an MVP deployment package for a medical-adjacent coordination helper. Do not commit real patient information, donor medical information, database passwords, API tokens, Firebase credentials, or private keys.

Keep `LIFELINK_AUTH_REQUIRED=true` in hosted environments. The hosted PostgreSQL adapter verifies Supabase JWT signatures and enforces ownership from verified claims. Rate limiting and audit logging are still pending; before production use, also use HTTPS, apply database least privilege, configure backups and retention, complete privacy and clinical-policy review, and distribute only a signed Android release.

To report a vulnerability, do not open a public issue containing sensitive details. Contact the repository owner privately through GitHub.
