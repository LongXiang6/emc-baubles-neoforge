# EMC Baubles for NeoForge

EMC-generating accessories, inventory helpers and building wands for ProjectE.

## Installation

Java 21, Minecraft 1.21.1, and NeoForge. Tested with 21.1.265. Download `emcbaubles-1.3.3.jar` from Releases. Install on both sides with Curios 9.6.0, EMC Precision 1.2.0 and its ProjectE companion. This port keeps the original item identifiers for existing worlds.

提供 EMC 项链、护符、腰带、手镯和建筑杖，适配 Curios 与小数 EMC。

## Build

Use JDK 21 and Python 3.11+. Create a text file with one absolute JAR path per line for the mapped Minecraft 1.21.1 and NeoForge 21.1.265 libraries, plus `curios-neoforge-9.6.0+1.21.1.jar`, `emc_precision-1.2.0.jar`, `ProjectE-1.21.1-PE1.1.0-precision.jar`. Put `neoforge-21.1.265-client.jar` first in the classpath.

```sh
python build.py --classpath-file classpath.txt --output emcbaubles-1.3.3.jar
```

## License and feedback

MIT. Unofficial port of EMC Baubles by JPAN_v2, who licenses the mod as MIT in its metadata. Original project: https://github.com/JPANv2/EMCBaubles/tree/08cafe37356f0dc8f2c2fdd5736807458a165d5d.

Report issues here with reproduction steps and your Minecraft, NeoForge and mod versions.
