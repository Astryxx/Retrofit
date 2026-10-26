"""Runs real Minecraft 1.7.10 + Forge 10.13.4.1614 with the reference mod, as the old launcher did,
offline, until it quits (or the time runs out). Screenshots end up in <game dir>/screenshots.

    python run_reference.py --java <Java 8 java.exe> --game <game dir> --jar <reference1710.jar>
                            --items <item list> [--minecraft <launcher dir>] [--seconds N] [mods...]

The launcher directory (default: %APPDATA%/.minecraft) needs the 1.7.10 and
1.7.10-Forge10.13.4.1614-1.7.10 versions with their libraries and assets.
"""
import argparse
import json
import os
import shutil
import subprocess
import time
import zipfile

FORGE = '1.7.10-Forge10.13.4.1614-1.7.10'

parser = argparse.ArgumentParser()
parser.add_argument('--java', required=True)
parser.add_argument('--game', required=True)
parser.add_argument('--jar', required=True)
parser.add_argument('--items', required=True)
parser.add_argument('--minecraft', default=os.path.join(os.environ.get('APPDATA', ''), '.minecraft'))
parser.add_argument('--seconds', type=int, default=2400)
parser.add_argument('mods', nargs='*')
args = parser.parse_args()


def version(name):
    with open(os.path.join(args.minecraft, 'versions', name, name + '.json'), encoding='utf-8') as f:
        return json.load(f)


def allowed(rules):
    """The launcher's library rules, for Windows."""
    if not rules:
        return True
    ok = False
    for rule in rules:
        os_rule = rule.get('os')
        if os_rule is None or os_rule.get('name') == 'windows':
            ok = rule['action'] == 'allow'
    return ok


forge, vanilla = version(FORGE), version('1.7.10')
natives = os.path.join(args.game, 'natives')
mods = os.path.join(args.game, 'mods')
os.makedirs(natives, exist_ok=True)
for old in os.listdir(natives):  # no sound (see README): no OpenAL left from an earlier run either
    if 'openal' in old.lower():
        os.remove(os.path.join(natives, old))
os.makedirs(mods, exist_ok=True)
for old in os.listdir(mods):
    if old.endswith(('.jar', '.zip')):
        os.remove(os.path.join(mods, old))
for mod in args.mods:
    shutil.copy(mod, mods)

classpath, seen = [], set()
for lib in forge['libraries'] + vanilla['libraries']:
    if not allowed(lib.get('rules')):
        continue
    group, name, ver = lib['name'].split(':')[:3]
    path = os.path.join(args.minecraft, 'libraries', *group.split('.'), name, ver, '%s-%s' % (name, ver))
    native = lib.get('natives', {}).get('windows')
    if native:  # native libraries are unpacked, not put on the class path
        with zipfile.ZipFile(path + '-' + native.replace('${arch}', '64') + '.jar') as jar:
            for entry in jar.namelist():
                if entry.endswith('.dll') and 'openal' not in entry.lower():  # no sound: see README
                    with open(os.path.join(natives, os.path.basename(entry)), 'wb') as out:
                        out.write(jar.read(entry))
        continue
    if (group, name) not in seen:
        seen.add((group, name))
        classpath.append(path + '.jar')
classpath += [args.jar, os.path.join(args.minecraft, 'versions', '1.7.10', '1.7.10.jar')]

values = {'${auth_player_name}': 'Dev', '${version_name}': FORGE, '${game_directory}': args.game,
          '${assets_root}': os.path.join(args.minecraft, 'assets'), '${assets_index_name}': vanilla['assets'],
          '${auth_uuid}': '00000000000000000000000000000000', '${auth_access_token}': '0',
          '${user_properties}': '{}', '${user_type}': 'legacy'}
game_args = []
for arg in forge['minecraftArguments'].split(' '):
    for key, value in values.items():
        arg = arg.replace(key, value)
    game_args.append(arg)
# the same window size as Retrofit's play test, so the screenshots line up
command = [args.java, '-Xmx3G', '-Djava.library.path=' + natives, '-Dretrofit.reference=' + args.items,
           '-Dfml.ignoreInvalidMinecraftCertificates=true', '-cp', os.pathsep.join(classpath),
           'retrofit.testmods.reference.ReferenceMain'] + game_args + ['--width', '1600', '--height', '900']
with open(os.path.join(args.game, 'launch.log'), 'w', encoding='utf-8') as log:
    process = subprocess.Popen(command, cwd=args.game, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + args.seconds
    while time.time() < deadline and process.poll() is None:
        time.sleep(2)
    if process.poll() is None:
        process.kill()
print('exit', process.poll())
