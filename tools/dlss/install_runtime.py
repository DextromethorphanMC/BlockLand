"""Installs NVIDIA's DLSS runtime (from the Streamline SDK) into a Minecraft game directory for BlockLand's DLSS.

python -I tools/dlss/install_runtime.py <Streamline SDK zip | extracted SDK folder | folder with the DLLs> <game dir> [...]
python -I tools/dlss/install_runtime.py --dlssnr <rtx-remix-ngx_sdk_dlnr@2.7z | its extracted folder> <game dir> [...]

Copies sl.interposer.dll, sl.common.dll, sl.dlss.dll, nvngx_dlss.dll (DLSS Super Resolution / DLAA), the files DLSS
Frame Generation adds (sl.dlss_g.dll, nvngx_dlssg.dll, sl.reflex.dll, sl.pcl.dll, NvLowLatencyVk.dll) and DLSS Ray
Reconstruction adds (sl.dlss_d.dll, nvngx_dlssd.dll) when the source has them, and their license texts into <game dir>/blockland/native/streamline (where client/graphics/upscale looks). The
binaries are NVIDIA's (Streamline SDK: https://github.com/NVIDIA-RTX/Streamline/releases) and are not part of the
BlockLand repository; a newer SDK or nvngx_dlss.dll can be installed over an older one the same way.

--dlssnr installs DLSS 5's neural rendering model (nvngx_dlssnr.dll and its license) from NVIDIA's DLSS-NR SDK package —
the one RTX Remix's public build downloads (https://d1oqksizpb0e3d.cloudfront.net/rtx-remix-ngx_sdk_dlnr@2.7z); a .7z
is unpacked with Windows' own tar (libarchive)."""
import os
import shutil
import sys
import zipfile

FILES = ["sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "nvngx_dlss.dll"]
FRAME_GENERATION = ["sl.dlss_g.dll", "nvngx_dlssg.dll", "sl.reflex.dll", "sl.pcl.dll", "NvLowLatencyVk.dll"]
RAY_RECONSTRUCTION = ["sl.dlss_d.dll", "nvngx_dlssd.dll"]
LICENSES = ["nvngx_dlss.license.txt", "nvngx_dlssg.license.txt", "nvngx_dlssd.license.txt"]


def find(source):
    """Name -> bytes reader for the needed files, from a zip, an SDK folder or a plain folder."""
    if zipfile.is_zipfile(source):
        archive = zipfile.ZipFile(source)
        names, top = {}, {}
        for n in archive.namelist():
            path = "/" + n.replace("\\", "/")
            # bin/x64/<file> directly (not bin/x64/development/...)
            if "/bin/x64/" in path and "/" not in path.split("/bin/x64/", 1)[1]:
                names[os.path.basename(n)] = n
            if path.count("/") <= 2:
                top[os.path.basename(n)] = n
        def read(name):
            if name in names:
                return archive.read(names[name])
            if name == "streamline.license.txt" and "license.txt" in top:
                return archive.read(top["license.txt"])
            return None
        return read
    folder = source
    if os.path.isdir(os.path.join(source, "bin", "x64")):
        folder = os.path.join(source, "bin", "x64")
    def read(name):
        path = os.path.join(folder, name)
        if name == "streamline.license.txt":
            path = os.path.join(source, "license.txt")
        if os.path.isfile(path):
            with open(path, "rb") as f:
                return f.read()
        return None
    return read


def install_dlssnr(source, games):
    """Copies nvngx_dlssnr.dll (x86-64) and the SDK license out of the DLSS-NR package into each game directory."""
    import subprocess
    import tempfile
    folder = source
    temp = None
    if os.path.isfile(source):
        temp = tempfile.mkdtemp(prefix="blockland-dlssnr-")
        tar = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32", "tar.exe")
        member = "lib/Windows_x86_64/rel/nvngx_dlssnr.dll"
        if subprocess.call([tar, "-xf", source, "-C", temp, member, "LICENSE.txt"]) != 0:
            sys.exit("could not unpack " + source)
        folder = temp
    dll = os.path.join(folder, "lib", "Windows_x86_64", "rel", "nvngx_dlssnr.dll")
    if not os.path.isfile(dll):
        sys.exit("nvngx_dlssnr.dll not found in " + source)
    for game in games:
        target = os.path.join(game, "blockland", "native", "streamline")
        os.makedirs(target, exist_ok=True)
        shutil.copyfile(dll, os.path.join(target, "nvngx_dlssnr.dll"))
        license_file = os.path.join(folder, "LICENSE.txt")
        if os.path.isfile(license_file):
            shutil.copyfile(license_file, os.path.join(target, "nvngx_dlssnr.license.txt"))
        print("installed DLSS 5 neural rendering into " + target)
    if temp:
        shutil.rmtree(temp, ignore_errors=True)


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--dlssnr":
        if len(sys.argv) < 4:
            sys.exit(__doc__)
        install_dlssnr(sys.argv[2], sys.argv[3:])
        return
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    read = find(sys.argv[1])
    missing = [name for name in FILES if read(name) is None]
    if missing:
        sys.exit("not found in " + sys.argv[1] + ": " + ", ".join(missing))
    absent = [name for name in FRAME_GENERATION if read(name) is None]
    if absent:
        print("no DLSS Frame Generation in this source (" + ", ".join(absent) + "): Super Resolution only")
    for game in sys.argv[2:]:
        target = os.path.join(game, "blockland", "native", "streamline")
        os.makedirs(target, exist_ok=True)
        for name in FILES + FRAME_GENERATION + RAY_RECONSTRUCTION + LICENSES + ["streamline.license.txt"]:
            data = read(name)
            if data is None:
                continue
            with open(os.path.join(target, name), "wb") as f:
                f.write(data)
        print("installed into " + target)


if __name__ == "__main__":
    main()
