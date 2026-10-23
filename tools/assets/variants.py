"""Re-uses a weapon's rig and clip modules for a sibling weapon with other shapes (VIEWMODEL_SPEC section 26: the USP
is built to the P9's contract and plays the P9's authored animation on its own geometry).

The rig and clip modules read their geometry from a module-level import (`import p9_model as pm`). load() executes
fresh copies of them while that import resolves to another geometry module, so the copies compute their nodes, hand
anchors and clips from the sibling's dimensions; the original modules (and the P9 they build) are untouched."""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(prefix, replace, chain, after=None):
    """Executes the modules named in `chain` (in order) as new modules `<prefix><name>`, with every name in `replace`
    (module name -> module object) and every earlier module of the chain standing in for its original during the
    imports. `after`: {module name: fn(module)} run right after a module executed (patch a constant before the next
    module reads it). Returns {name: new module}."""
    after = after or {}
    names = list(replace) + list(chain)
    saved = {n: sys.modules.get(n) for n in names}
    out = {}
    try:
        for name, module in replace.items():
            sys.modules[name] = module
        for name in chain:
            spec = importlib.util.spec_from_file_location(prefix + name, os.path.join(HERE, name + ".py"))
            module = importlib.util.module_from_spec(spec)
            sys.modules[name] = module                  # later modules of the chain import this copy
            spec.loader.exec_module(module)
            if name in after:
                after[name](module)
            out[name] = module
    finally:
        for name, module in saved.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module
    return out
