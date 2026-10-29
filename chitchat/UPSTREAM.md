# Repository layout

The repository root preserves the upstream BitChat Android project and its GPL-3.0 license.

The `chitchat/` folder is an independent Java/Swift implementation under its own MIT license. It is built by `.github/workflows/chitchat-build.yml`. It does not incorporate BitChat code, implement BitChat mesh relay, or communicate with BitChat clients. The fork provides a separate build location for the prepared app.

Reference projects reviewed:
- https://github.com/permissionlesstech/bitchat-android (Android; the actual LICENSE.md is GPL-3.0).
- https://github.com/permissionlesstech/bitchat (iOS; LICENSE contains the Unlicense).

No upstream transport or cryptography is changed by the independent app.
