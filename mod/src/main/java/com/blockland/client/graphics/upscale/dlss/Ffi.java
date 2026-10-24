package com.blockland.client.graphics.upscale.dlss;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;

import java.nio.ByteBuffer;

/**
 * Calls C functions by address through LWJGL's libffi (part of LWJGL's core, which Minecraft always loads), so the DLSS
 * bridge needs no native code of its own and no extra library: Vulkan, Streamline and NGX are plain DLL exports. Every
 * argument and return value is carried as a {@code long} (pointers, handles, 32-bit integers, bytes; a float argument as
 * its bits, see {@link #f32}). C++ virtual methods are called the same way through their vtable slot ({@link #virt}).
 */
final class Ffi {
    enum Type {
        VOID(LibFFI.ffi_type_void), I32(LibFFI.ffi_type_sint32), U32(LibFFI.ffi_type_uint32), I64(LibFFI.ffi_type_sint64),
        PTR(LibFFI.ffi_type_pointer), U8(LibFFI.ffi_type_uint8), F32(LibFFI.ffi_type_float), F64(LibFFI.ffi_type_double);

        final FFIType ffi;

        Type(FFIType ffi) {
            this.ffi = ffi;
        }
    }

    /** One native function with its signature prepared once. */
    static final class Fn {
        final String name;
        private final long address;
        private final FFICIF cif;
        private final Type ret;
        private final int arity;
        /** Kept alive: the call interface points at it. */
        @SuppressWarnings("unused")
        private final PointerBuffer types;

        private Fn(String name, long address, Type ret, Type... args) {
            this.name = name;
            this.address = address;
            this.ret = ret;
            this.arity = args.length;
            this.cif = FFICIF.calloc();
            this.types = MemoryUtil.memAllocPointer(Math.max(1, args.length));
            for (int i = 0; i < args.length; i++) types.put(i, args[i].ffi.address());
            int status = LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, ret.ffi, args.length == 0 ? null : types);
            if (status != LibFFI.FFI_OK) throw new IllegalStateException("ffi_prep_cif failed for " + name + ": " + status);
        }

        /** Calls the function; integer results are sign-extended (I32) or zero-extended (U32, U8). */
        long call(long... values) {
            if (values.length != arity) throw new IllegalArgumentException(name + " takes " + arity + " arguments");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointers = stack.mallocPointer(Math.max(1, arity));
                for (int i = 0; i < arity; i++) {
                    // each value in an 8-byte slot: little-endian, so narrower types read their low bytes
                    ByteBuffer slot = stack.malloc(8, 8);
                    slot.putLong(0, values[i]);
                    pointers.put(i, MemoryUtil.memAddress(slot));
                }
                ByteBuffer result = stack.calloc(8, 8);
                LibFFI.ffi_call(cif, address, result, arity == 0 ? null : pointers);
                return switch (ret) {
                    case VOID -> 0;
                    case I32 -> result.getInt(0);
                    case U32 -> result.getInt(0) & 0xFFFF_FFFFL;
                    case U8 -> result.get(0) & 0xFF;
                    case I64, PTR, F64 -> result.getLong(0);
                    case F32 -> result.getInt(0) & 0xFFFF_FFFFL;
                };
            }
        }

        int callInt(long... values) {
            return (int) call(values);
        }
    }

    private Ffi() {
    }

    /** A float argument: its bits in the low half of the slot (libffi reads four bytes). */
    static long f32(float value) {
        return Float.floatToRawIntBits(value) & 0xFFFF_FFFFL;
    }

    /** A double argument: its bits. */
    static long f64(double value) {
        return Double.doubleToRawLongBits(value);
    }

    /** Virtual method {@code slot} of the C++ object at {@code object} (MSVC x64: the vtable pointer is its first field). */
    static Fn virt(String name, long object, int slot, Type ret, Type... args) {
        long vtable = org.lwjgl.system.MemoryUtil.memGetAddress(object);
        return fn(name, org.lwjgl.system.MemoryUtil.memGetAddress(vtable + 8L * slot), ret, args);
    }

    /** Prepares a function; {@code address} 0 throws (a missing export is a missing feature). */
    static Fn fn(String name, long address, Type ret, Type... args) {
        if (address == 0) throw new DlssException("blockland.graphics.status.dlss.missing_function", name);
        return new Fn(name, address, ret, args);
    }
}
