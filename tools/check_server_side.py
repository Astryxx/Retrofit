"""Checks that the legacy bridge's server-side classes only use 1.7.10 members a dedicated server has.

1.7.10 strips client-only (@SideOnly(CLIENT)) methods and fields from the server, so a call that
works in singleplayer can fail with NoSuchMethodError on a dedicated server. This compares every
net.minecraft / cpw member referenced by the compiled bridge (minus its client-only classes)
with the server's own classes, dumped by `gradlew :harness:dumpServerSrgJar`.

Usage: python -I tools/check_server_side.py   (after building legacy-bridge and dumping)
"""
import pathlib
import re
import subprocess
import sys
import shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent
SERVER_JAR = ROOT / 'run' / 'legacy-srg' / 'minecraft-1.7.10-forge-srg-server.jar'
CLASSES = ROOT / 'legacy-bridge' / 'build' / 'classes' / 'java' / 'main'
# Classes only ever loaded on clients (see their docs).
CLIENT_ONLY = {'ClientGuis', 'ClientTextures', 'BridgeRenderGlobal', 'ClientEvents', 'ClientPlayers', 'ClientWorlds', 'BridgeClientPlayer', 'BridgeClientNetHandler',
               'BridgeSoundHandler', 'BridgeEffectRenderer', 'BridgeSkinManager', 'BridgeWorldClient',
               'LegacyRendering', 'LegacyIcons', 'CameraEntity'}

# Known and fine: called only on clients, guarded, or not 1.7.10 classes at all.
ALLOWED = {
    'net/minecraft/block/Block.func_149701_w',          # getRenderBlockPass: caught (Bridge.blockRenderInfo)
    'net/minecraft/block/Block.func_149734_b',          # randomDisplayTick: only from animateTick, a client method
    'net/minecraft/block/Block.func_149694_d',          # getItem (pick block): caught (Interactions.pickStack)
    'net/minecraft/inventory/Container.func_75137_b',   # updateProgressBar: only client menus call it
    'net/minecraft/item/EnumAction.ordinal',            # java.lang.Enum
    'net/minecraft/entity/EnumCreatureType.ordinal',    # java.lang.Enum
    'net/minecraft/world/World.func_72802_i',           # light for ChunkCache.func_72802_i, itself client-only
    'net/minecraft/entity/DataWatcher.func_75687_a',    # updateWatchedObjectsFromList: Entities.entityApplyWatcher, clients only
    'net/minecraft/entity/Entity.func_70034_d',         # setRotationYawHead: Entities.entityClientTick, clients only
    'net/minecraft/entity/Entity.func_70056_a',         # setPositionAndRotation2: Entities.entityClientTick, clients only
    'net/minecraft/entity/Entity.func_70103_a',         # handleHealthUpdate: Entities.entityEvent, clients only
    'class net/minecraft/launchwrapper/Launch',         # LaunchWrapper, not part of the game jar
    'class net/minecraft/client/network/NetHandlerPlayClient',  # LegacyWorlds.clientNetHandler: clients only
    'cpw/mods/fml/common/network/FMLEmbeddedChannel.attr',          # netty EmbeddedChannel
    'cpw/mods/fml/common/network/FMLEmbeddedChannel.writeInbound',  # netty EmbeddedChannel
}

JAVAP = shutil.which('javap') or r'C:\Program Files\Java\jdk-17\bin\javap.exe'
_members = {}


def members(owner):
    """Names of the fields and methods a server class has, with its superclasses and interfaces."""
    if owner in _members:
        return _members[owner]
    out = subprocess.run([JAVAP, '-p', '-cp', str(SERVER_JAR), owner.replace('/', '.')],
                         capture_output=True, text=True).stdout
    names = set()
    parents = []
    for line in out.splitlines():
        header = re.match(r'.*(?:class|interface) \S+(?: extends ([\w.$, ]+?))?(?: implements ([\w.$, ]+))? \{', line)
        if header:
            for group in header.groups():
                if group:
                    parents += [p.strip().replace('.', '/') for p in group.split(',')]
            continue
        match = re.match(r'\s+.*?(\w+)(\(|;)', line)
        if match:
            names.add(match.group(1))
    if not out:
        names = None  # class missing on the server
    else:
        for parent in parents:
            if parent.startswith(('net/minecraft', 'cpw/')):
                inherited = members(parent)
                if inherited:
                    names |= inherited
    _members[owner] = names
    return names


def main():
    if not SERVER_JAR.is_file():
        sys.exit('Run gradlew :harness:dumpServerSrgJar first')
    problems = []
    checked = 0
    for path in sorted(CLASSES.rglob('*.class')):
        if path.stem.split('$')[0] in CLIENT_ONLY:
            continue
        out = subprocess.run([JAVAP, '-c', '-p', str(path)], capture_output=True, text=True).stdout
        for match in re.finditer(r'// (?:Method|InterfaceMethod|Field) ([\w/$]+)\.("?[\w<>]+"?):', out):
            owner, member = match.group(1), match.group(2).strip('"')
            if not owner.startswith(('net/minecraft', 'cpw/')) or member == '<init>':
                continue
            checked += 1
            names = members(owner)
            if names is None and f'class {owner}' not in ALLOWED:
                problems.append(f'{path.stem}: class {owner} does not exist on servers')
            elif names is not None and member not in names and f'{owner}.{member}' not in ALLOWED:
                problems.append(f'{path.stem}: {owner}.{member} does not exist on servers')
    for problem in sorted(set(problems)):
        print(problem)
    print(f'{checked} references checked, {len(set(problems))} problems')
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
