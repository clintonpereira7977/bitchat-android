# ChitChat beta 4: retained-alert notification handling

Native Android update, version 0.3.0-beta4 / 6. The native display changes need no
Cloudflare deployment. The separate service 0.1.3 repair removes merging of
pending inbox pushes and does require a server rollout; see REPEAT-NOTIFICATIONS.md.

The owner reports beta 3 still suppresses repeat alerts while an earlier item
remains in the tray on multiple phones. The physical cause is not established.
This release adds explicit notification grouping and distinct numeric child IDs
alongside the existing unique tags. Children request alerts; the stable group
summary is silent. It keeps the existing channel, user sound choices, generic
private text, consent checks, bounded six children and duplicate suppression.
Opt-out and opening Online also clear the summary and pre-update child IDs.

Local counters in Check notifications distinguish push callbacks from posted
alerts without retaining message text or exposing identifiers. The settings
check now reports a channel with no sound. Android can still suppress sound
through cooldown or Do Not Disturb; no phone policy is bypassed. A callback or
native post is not proof of audible delivery. Physical phone validation remains
required, including immediate second and third messages with the screen locked.

New native checks retain existing alerts and post more while the emulator screen
is off, assert fresh tags/IDs and explicit child-alert grouping, inspect the
silent summary, verify cleanup and beta 3 legacy cancellation, and test silent
channel diagnostics. These are local notification-path checks, not FCM delivery
or physical audio tests. Existing Online and Bluetooth checks still run.

Release publication is blocked unless the signed installer certificate matches
the permanent published beta 3 APK. The publisher records that result and keeps
all older APKs. Opening beta 4 over a compatible beta 3 should offer Update and
preserve identity and contacts. Stop on a signature mismatch; do not uninstall,
clear data or regenerate keys. This release does not include an in-app updater.
