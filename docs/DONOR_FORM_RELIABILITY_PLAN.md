# Donor Form Reliability Plan

## Problem

The donor profile editor receives profile emissions when automatic location capture, map selection, availability changes, and background refreshes complete. The editor previously treated those emissions as reasons to recreate draft state, so a donor could type a name, note, radius, or blood type and lose the unsaved values while selecting a location.

## Implemented fix

The editor now owns a stable draft for the lifetime of the visible donor editor. Draft fields are not keyed to individual server fields or map events. Automatic location capture updates only latitude, longitude, and precision in the profile state. Manual coordinate fields are synchronized with confirmed location updates, while actively edited coordinate text is preserved until the donor explicitly applies it.

## Improvement rules

1. **Separate draft state from saved profile state.** Background refreshes must never overwrite unsaved text, chips, switches, or numeric input.
2. **Treat location as a single action.** Current-location capture, map taps, and manual coordinates all update only the location portion of the draft.
3. **Keep map controls presentation-only.** Recenter and retry actions must not recreate the form or reset its draft.
4. **Validate before packaging.** Android unit tests, lint, and compilation must pass before producing a debug APK. Emulator smoke tests are reported separately because device installation can fail independently of APK compilation.
5. **Do not claim medical eligibility.** Profile completion enables operational matching only; health screening remains a separate future workflow.

## Verification sequence

The release sequence is: run Android unit tests and lint, build the debug APK, run emulator smoke tests when the hosted device is healthy, and upload the debug artifact only after the debug build succeeds.
