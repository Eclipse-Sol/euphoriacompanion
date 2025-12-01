# Euphoria Companion

New 2.0.0 rewrite of the mod, should be a *lot* faster and more reliable.

## Features

- **Shaderpack Analysis**: Scans `.zip` or directory-based shaderpacks for `block.properties` files.
- **Block Comparison**:
  - Compares blocks registered in-game with those defined in shaderpacks.
  - Lets you know if some some reason the render layers are not properly matching due to mods interferance.

- **Entity Listing**:
  - You can now find a list of entities within the game within `logs/euphoriacompanion`.

## Usage

1. Launch the game with the mod installed.
2. Press `F6` to process block.properties again. (Rebindable)
3. Check the `logs/euphoriacompanion` folder for generated reports.

## How It Works

1. **Initialization**:
   - Scans the Minecraft instance's `shaderpacks` directory for shaderpacks.
   - Collects all registered blocks from the game (vanilla and modded).

2. **Shaderpack Processing**:
   - Extracts and parses `shaders/block.properties` from shaderpacks (supports both folders and `.zip` files).

3. **Comparison**:
   - Compares in-game blocks with shader-defined blocks.
   - Detects:
     - Blocks present in-game but missing from the shaderpack.

4. **Reporting**:
   - Writes a categorized report to `logs/euphoriacompanion/[shaderpack_name].txt`.

## Installation

1. **Requires Fabric Loader** and **Fabric API**. You can also you Sinytra Connector for 1.20.1 and 1.21.1.
2. Place the mod JAR in your `mods` folder.

