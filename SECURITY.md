# Security Policy

LifeLink Cloud is an MVP deployment package for a medical-adjacent coordination helper. Do not commit real patient information, donor medical information, database passwords, API tokens, Firebase credentials, or private keys.

Keep `LIFELINK_AUTH_REQUIRED=true` in hosted environments. The current bearer-token parser is a development seam, not verified identity. Before production use, replace it with verified JWT/Firebase/Supabase Auth validation, enforce ownership from verified claims, add rate limiting and audit logging, use HTTPS, apply database least privilege, configure backups and retention, and complete privacy and clinical-policy review.

To report a vulnerability, do not open a public issue containing sensitive details. Contact the repository owner privately through GitHub.
