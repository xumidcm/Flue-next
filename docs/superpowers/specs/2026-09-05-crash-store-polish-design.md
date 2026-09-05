# Crash and Watch Store Polish

## Scope

This change covers five small user-facing improvements:

- Rename the loading escape action to "Switch to the default watch face and restart".
- Set the application version to `2.2.1`, including the Settings/About display.
- Randomize crash-page title and subtitle variants, add surprised kaomoji to both, and add a "Desktop settings" button that opens Flue's `SettingsActivity`.
- Show the watch-face author beside download counts in both grid and list market cards.
- Make the top-creators page support top-of-list pull-to-refresh with a visible refresh state and duplicate-request protection.

## Design

Crash strings are stored as localized string arrays. A small pure selector chooses independent title and subtitle indices while ensuring the indices differ when more than one variant exists. The selected pair is held for the lifetime of the crash screen, so recomposition does not change the message. The existing system application-details action remains available; the new Desktop settings action starts the app's existing settings activity directly.

Market cards reuse the existing author field from `WatchStoreWatchFace`. The author segment is omitted when blank, and the existing compact metadata typography remains unchanged so it continues to fit round screens.

The top-creators overlay keeps refresh state in `WatchStoreCreatorsSession`. Its existing `StoreRoundList` refresh callback is the single request path, guarded against initial loading, load-more, and an active refresh. A refresh replaces the first-page snapshot, retains old content on failure, and exposes retry when no content exists.

## Verification

Add focused unit tests for crash-message index selection and author metadata formatting. Run the focused test suite, `git diff --check`, and the Windows-native Release build. Only reviewed implementation paths are included in the implementation commit.
