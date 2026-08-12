# Cloudify

Cloudify is a Minecraft mod that lets players back up their worlds, ~~instances, and servers~~ to their Google Drive.

## Download

You can download Cloudify from [Modrinth](https://modrinth.com/project/cloudify) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/cloudify). Alternatively, you can download the .jars directly from this repository's [Releases](https://github.com/tomatopotato17265/cloudify/releases/latest).

## Features

- Log in and out with Google Drive using OAuth
- Upload singleplayer worlds and instances to a dedicated folder in Google Drive
- Import worlds ~~and instances~~ from Google Drive, allowing you to seamlessly transfer data across machines or recover any lost data
- Manage data stored in Google Drive without leaving the game
- ~~Server admins can also back up their servers to protect against unforeseen accidents~~

## Development

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using. We recommend using IntelliJ IDEA.

Begin by cloning the repository in a directory of your choice:
```
git clone https://github.com/tomatopotato17265/cloudify
cd cloudify-main
```

Then, make a copy of [secrets.properties.example](secrets.properties.example), and rename it to `secrets.properties`. Replace the placeholder values with your actual Google Cloud project's Client ID and Secret.

## Credits

Cloudify is licensed under the GNU General Public License Version 3. Please refer to the [license](LICENSE) for more details.

This project's Google Drive integration is built on the following Google Cloud dependencies, each licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0):

- [Google API Client Library for Java](https://github.com/googleapis/google-api-java-client)
- [Google OAuth Client Library for Java](https://github.com/googleapis/google-oauth-java-client)
- [Google Drive API v3 Client Library for Java](https://github.com/googleapis/google-api-java-client-services)