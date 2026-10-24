"""Compiles every shader program listed in programs.txt against this machine's OpenGL driver (hidden window, no focus).

python -I tools/shaders/check_shaders.py [programs.txt]

Lookup order for shader files: the lighting pack's overrides (resourcepacks/blockland_lighting), the mod's assets, the
vanilla assets (extracted once from the Minecraft client jar in the Gradle cache). Exit code 0 when all programs compile
and link. Uses LWJGL from the Gradle cache and a Java 21+ runtime (single-file source launch)."""
import glob
import os
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
RESOURCES = os.path.join(REPO, "mod", "src", "main", "resources")
PACK = os.path.join(RESOURCES, "resourcepacks", "blockland_lighting")
GRADLE = os.path.join(os.path.expanduser("~"), ".gradle")


def java():
    for pattern in (os.path.join(GRADLE, "jdks", "*21*", "bin", "java.exe"), r"C:\Program Files\Java\jdk-2*\bin\java.exe"):
        found = sorted(glob.glob(pattern))
        if found:
            return found[-1]
    return "java"


def lwjgl():
    jars = []
    for module in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl"):
        base = os.path.join(GRADLE, "caches", "modules-2", "files-2.1", "org.lwjgl", module, "3.3.3")
        api = [p for p in glob.glob(os.path.join(base, "*", module + "-3.3.3.jar"))]
        natives = glob.glob(os.path.join(GRADLE, "caches", "minecraftforge", "**", module + "-3.3.3-natives-windows.jar"), recursive=True)
        if not api or not natives:
            sys.exit("LWJGL " + module + " not found in the Gradle cache (build the mod once)")
        jars += [api[0], natives[0]]
    return jars


def vanilla():
    target = os.path.join(tempfile.gettempdir(), "blockland-shadercheck", "vanilla-1.21.8")
    marker = os.path.join(target, ".done")
    if os.path.exists(marker):
        return target
    client = glob.glob(os.path.join(GRADLE, "caches", "minecraftforge", "**", "minecraft_tasks", "1.21.8", "client.jar"), recursive=True)
    if not client:
        sys.exit("Minecraft 1.21.8 client.jar not found in the Gradle cache")
    with zipfile.ZipFile(client[0]) as jar:
        for name in jar.namelist():
            if name.startswith("assets/minecraft/shaders/"):
                jar.extract(name, target)
    open(marker, "w").close()
    return target


def main():
    programs = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "programs.txt")
    cmd = [java(), "-cp", os.pathsep.join(lwjgl()), os.path.join(HERE, "ShaderCheck.java"), programs, PACK, RESOURCES, vanilla()]
    sys.exit(subprocess.call(cmd))


if __name__ == "__main__":
    main()
