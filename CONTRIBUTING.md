# Contributing to Cloudify

Thank you for contributing to Cloudify! Here are some tips and info to help you get started with contribution.

## Project Information

Cloudify is fully written in Java for easy compatibility with Minecraft: Java Edition and multiple mod loaders. Cloudify currently supports [Fabric](https://fabricmc.net) ~~and [NeoForge](https://neoforged.net). Multi-loader and multi-version support is enabled by [Stonecutter](https://stonecutter.kikugie.dev).~~

## Setting up a local repository

Begin by cloning the repository in a directory of your choice:
```
git clone https://github.com/tomatopotato17265/cloudify
cd cloudify
```

You can now open the project in an IDE of your choice. We recommend using [IntelliJ IDEA](https://www.jetbrains.com/idea/), as it is a powerful IDE for Minecraft mod development.

Now that you have the project open, make a copy of [secrets.properties.example](secrets.properties.example), and rename it to `secrets.properties`. Replace the placeholder values with your actual Google Cloud project's Client IDs and Secrets.

That's it! You can now start editing the code and making any changes you'd like. When you're ready to share them, create a [Pull Request](https://github.com/tomatopotato17265/cloudify/pulls). Thank you for helping us improve Cloudify for everyone!
