package com.blockland.client.perf;

import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Vanilla's own profiler sections as {@link FrameTimings} spans (keys {@code "mc/<path>"}, e.g.
 * {@code "mc/gameRenderer/level/entities"}), down to {@value #MAX_DEPTH} levels below the root for the frame's render
 * ({@code gameRenderer}) and {@value #OTHER_DEPTH} elsewhere (the ticks push a section per entity). Handed to the client's
 * loop next to whatever profiler vanilla uses that frame, only while timings are recorded; its start and end are the
 * frame's (one iteration of the client loop: ticks, render, swap).
 */
final class VanillaSections implements ProfilerFiller {
    static final VanillaSections INSTANCE = new VanillaSections();
    private static final int MAX_DEPTH = 5, OTHER_DEPTH = 2;

    /** A section path, its children by name, its span key (paths are built once) and how deep its subtree is measured. */
    private static final class Node {
        final String key;
        final int depthLimit;
        final Map<String, Node> children = new HashMap<>();

        Node(String key, int depthLimit) {
            this.key = key;
            this.depthLimit = depthLimit;
        }

        Node child(String name, boolean top) {
            Node node = children.get(name);
            if (node == null) {
                int limit = top ? ("gameRenderer".equals(name) ? MAX_DEPTH : OTHER_DEPTH) : depthLimit;
                children.put(name, node = new Node(key + "/" + name, limit));
            }
            return node;
        }
    }

    private final Node root = new Node("mc", MAX_DEPTH);
    private final Node[] nodes = new Node[256];
    private final FrameTimings.Span[] spans = new FrameTimings.Span[256];
    private int depth;

    private VanillaSections() {
    }

    /** The client loop's iteration starts (ticks, the frame, the swap): a frame of {@link FrameTimings}. */
    @Override
    public void startTick() {
        closeAll();
        FrameTimings.beginFrame();
    }

    @Override
    public void endTick() {
        closeAll();
        FrameTimings.endFrame();
    }

    private void closeAll() {
        while (depth > 0) pop();
    }

    @Override
    public void push(String name) {
        if (depth >= nodes.length) {
            depth++;
            return;
        }
        Node parent = depth == 0 ? root : nodes[depth - 1];
        if (parent == null || depth >= parent.depthLimit) {
            nodes[depth] = null;
            spans[depth] = null;
        } else {
            Node node = parent.child(name, depth == 0);
            nodes[depth] = node;
            spans[depth] = FrameTimings.span(node.key);
        }
        depth++;
    }

    @Override
    public void push(Supplier<String> name) {
        // names below the measured depth are never built
        Node parent = depth == 0 ? root : depth < nodes.length ? nodes[depth - 1] : null;
        if (parent != null && depth < parent.depthLimit) push(name.get());
        else push("");
    }

    @Override
    public void pop() {
        if (depth == 0) return;
        depth--;
        if (depth < spans.length && spans[depth] != null) {
            spans[depth].close();
            spans[depth] = null;
        }
    }

    @Override
    public void popPush(String name) {
        pop();
        push(name);
    }

    @Override
    public void popPush(Supplier<String> name) {
        pop();
        push(name);
    }

    @Override
    public void markForCharting(MetricCategory category) {
    }

    @Override
    public void incrementCounter(String name, int count) {
    }

    @Override
    public void incrementCounter(Supplier<String> name, int count) {
    }
}
