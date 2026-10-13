"""Lists which 1.7.10 Minecraft/Forge systems legacy mod jars depend on.

Reads class constant pools (no decompiling), counts referenced classes from net.minecraft,
cpw.mods.fml, net.minecraftforge and org.lwjgl, groups them into Retrofit subsystems, and
reports which of those Retrofit already bridges. Usage:

    python -I tools/analyze_mods.py corpus/*.jar
"""
import collections
import re
import struct
import sys
import zipfile

# Subsystem -> (class name patterns, bridged by Retrofit yet?)
SUBSYSTEMS = [
    ("Blocks / tile entities", [r"net/minecraft/block/", r"net/minecraft/tileentity/TileEntity$"], True),
    ("Items / ItemStacks", [r"net/minecraft/item/Item(Stack)?$", r"net/minecraft/item/ItemBlock"], True),
    ("NBT", [r"net/minecraft/nbt/"], True),
    ("Block icons / textures", [r"IIconRegister", r"net/minecraft/util/IIcon$"], True),
    ("Tile entity special renderers (TESR)", [r"TileEntitySpecialRenderer", r"ClientRegistry"], False),
    ("Block render handlers (ISBRH)", [r"ISimpleBlockRenderingHandler", r"RenderingRegistry", r"RenderBlocks"], False),
    ("Item renderers (IItemRenderer)", [r"IItemRenderer", r"MinecraftForgeClient"], False),
    ("Models (ModelBase/Techne/OBJ)", [r"net/minecraft/client/model/", r"IModelCustom", r"AdvancedModelLoader"], False),
    ("Raw GL / Tessellator", [r"org/lwjgl/opengl/", r"net/minecraft/client/renderer/Tessellator", r"OpenGlHelper"], False),
    ("GUIs / screens", [r"net/minecraft/client/gui/", r"IGuiHandler", r"NetworkRegistry"], False),
    ("Containers / slots / inventories", [r"net/minecraft/inventory/"], False),
    ("Players / interaction", [r"net/minecraft/entity/player/"], False),
    ("Entities", [r"net/minecraft/entity/(?!player/)", r"EntityRegistry"], False),
    ("Mod networking", [r"SimpleNetworkWrapper", r"FMLProxyPacket", r"FMLEventChannel", r"IMessage"], False),
    ("Event bus handlers", [r"cpw/mods/fml/common/eventhandler/SubscribeEvent", r"net/minecraftforge/event/"], False),
    ("Recipes / crafting", [r"net/minecraft/item/crafting/", r"GameRegistry", r"OreDictionary"], False),
    ("World generation", [r"IWorldGenerator", r"net/minecraft/world/gen/", r"BiomeGenBase"], False),
    ("Sounds", [r"net/minecraft/client/audio/", r"playSound"], False),
    ("Config files", [r"net/minecraftforge/common/config/"], True),
]
TRACKED = re.compile(rb"(?:net/minecraft|cpw/mods/fml|net/minecraftforge|org/lwjgl)/[A-Za-z0-9_/$]+")


def class_names(data):
    """Class and member-owner names from a class file's constant pool (UTF8 entries)."""
    count = struct.unpack(">H", data[8:10])[0]
    index, i, names = 10, 1, set()
    while i < count:
        tag = data[index]
        if tag == 1:
            length = struct.unpack(">H", data[index + 1:index + 3])[0]
            text = data[index + 3:index + 3 + length]
            names.update(match.decode() for match in TRACKED.findall(text))
            index += 3 + length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            index += 5
        elif tag in (5, 6):
            index += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            index += 3
        elif tag == 15:
            index += 4
        else:
            break
        i += 1
    return names


def analyze(path):
    references = collections.Counter()
    gl_calls = collections.Counter()
    classes = 0
    coremod = False
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode(errors="ignore") if "META-INF/MANIFEST.MF" in jar.namelist() else ""
        coremod = "FMLCorePlugin" in manifest
        for entry in jar.namelist():
            if not entry.endswith(".class"):
                continue
            data = jar.read(entry)
            if data[:4] != b"\xca\xfe\xba\xbe":
                continue
            classes += 1
            for name in class_names(data):
                references[name] += 1
            gl_calls.update(m.decode() for m in re.findall(rb"gl[A-Z][A-Za-z0-9]+", data))
    return classes, coremod, references, gl_calls


def main(paths):
    for path in paths:
        classes, coremod, references, gl_calls = analyze(path)
        print(f"\n=== {path.split('/')[-1].split(chr(92))[-1]}: {classes} classes{', COREMOD' if coremod else ''}")
        for name, patterns, bridged in SUBSYSTEMS:
            hits = {ref: n for ref, n in references.items() if any(re.search(p, ref) for p in patterns)}
            if hits:
                top = ", ".join(f"{ref.split('/')[-1]}({n})" for ref, n in sorted(hits.items(), key=lambda kv: -kv[1])[:6])
                status = "bridged" if bridged else "NEEDED"
                print(f"  [{status:7}] {name}: {sum(hits.values())} refs - {top}")
        if gl_calls:
            print("  GL calls: " + ", ".join(f"{c}({n})" for c, n in gl_calls.most_common(25)))


if __name__ == "__main__":
    main(sys.argv[1:])
