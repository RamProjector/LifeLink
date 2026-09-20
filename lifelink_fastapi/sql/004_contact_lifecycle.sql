-- Explicit requester/donor coordination states after donor acceptance.
ALTER TABLE donor_contact_requests DROP CONSTRAINT IF EXISTS donor_contact_requests_status_check;
ALTER TABLE donor_contact_requests ADD CONSTRAINT donor_contact_requests_status_check
    CHECK (status IN ('pending', 'accepted', 'declined', 'contact_shared', 'meeting_arranged', 'fulfilled', 'cancelled', 'expired'));
