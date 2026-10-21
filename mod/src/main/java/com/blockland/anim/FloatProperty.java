package com.blockland.anim;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Something a tween can animate: one float read and written through a getter/setter pair. */
public interface FloatProperty {

    float get();

    void set(float value);

    static FloatProperty of(Supplier<Float> getter, Consumer<Float> setter) {
        return new FloatProperty() {
            @Override
            public float get() {
                return getter.get();
            }

            @Override
            public void set(float value) {
                setter.accept(value);
            }
        };
    }
}
