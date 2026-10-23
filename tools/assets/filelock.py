"""A tiny cross-process lock for files that several generators update in place (sounds.json): parallel builds of
different weapons each merge their own entries without losing the others'."""
import contextlib
import os
import time


@contextlib.contextmanager
def locked(path, timeout=120.0):
    """Holds `<path>.lock` (created exclusively) while the block runs; stale locks older than `timeout` are broken."""
    lock = path + ".lock"
    start = time.time()
    while True:
        try:
            fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            break
        except FileExistsError:
            try:
                if time.time() - os.path.getmtime(lock) > timeout:
                    os.remove(lock)
                    continue
            except OSError:
                pass
            if time.time() - start > timeout * 2:
                raise TimeoutError(f"cannot lock {path}")
            time.sleep(0.05)
    try:
        yield
    finally:
        os.close(fd)
        try:
            os.remove(lock)
        except OSError:
            pass
