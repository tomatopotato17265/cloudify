# Cloudify

Cloudify is a Minecraft mod that lets players back up their worlds, instances, and servers to their Google Drive.

## Download

You can download Cloudify from [Modrinth](https://modrinth.com/project/cloudify) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/cloudify). Alternatively, you can download the .jars directly from this repository's [Releases](https://github.com/tomatopotato17265/cloudify/releases/latest).

## Features

- Log in and out with Google Drive using OAuth
- Upload singleplayer worlds and instances to a dedicated folder in Google Drive
- Import worlds and instances from Google Drive, allowing you to seamlessly transfer data across machines or recover any lost data
- Manage data stored in Google Drive without leaving the game
- Server admins can also back up their servers to protect against unforeseen accidents

## Contributing to Cloudify

Please refer to the [Contributing Guidelines](CONTRIBUTING.md) for more information on how to suggest improvements to Cloudify.

## License and Privacy Policy

Cloudify is licensed under the GNU General Public License Version 3. Please refer to the [license](LICENSE) for more details.

This project's Google Drive integration is built on the following Google Cloud dependencies, each licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0):

- [Google API Client Library for Java](https://github.com/googleapis/google-api-java-client)
- [Google OAuth Client Library for Java](https://github.com/googleapis/google-oauth-java-client)
- [Google Drive API v3 Client Library for Java](https://github.com/googleapis/google-api-java-client-services)

Cloudify collects your email address via the `userinfo.email` scope and manages files and folders in your Google Drive via the `drive.file` scope. Nothing is collected and sent to another server; everything stays between the client-side mod and Google. Please refer to the [Privacy Policy](https://cloudifymc.dev/privacy.html) for more information.
