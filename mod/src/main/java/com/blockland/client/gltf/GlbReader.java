package com.blockland.client.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Reads binary glTF 2.0 (GLB) into a {@link GltfModel}. */
public final class GlbReader {
    private static final int MAGIC = 0x46546C67;   // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private final JsonObject json;
    private final ByteBuffer bin;

    private GlbReader(JsonObject json, ByteBuffer bin) {
        this.json = json;
        this.bin = bin;
    }

    public static GltfModel read(byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.remaining() < 20 || buf.getInt() != MAGIC) throw new IOException("not a GLB file");
        int version = buf.getInt();
        if (version != 2) throw new IOException("unsupported glTF version " + version);
        buf.getInt(); // total length
        JsonObject json = null;
        ByteBuffer bin = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.remaining() >= 8) {
            int length = buf.getInt();
            int type = buf.getInt();
            if (length < 0 || length > buf.remaining()) throw new IOException("truncated GLB chunk");
            if (type == CHUNK_JSON) {
                json = JsonParser.parseString(new String(data, buf.position(), length, StandardCharsets.UTF_8)).getAsJsonObject();
            } else if (type == CHUNK_BIN) {
                bin = ByteBuffer.wrap(data, buf.position(), length).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            buf.position(buf.position() + length);
        }
        if (json == null) throw new IOException("GLB without JSON chunk");
        return new GlbReader(json, bin).build();
    }

    private GltfModel build() throws IOException {
        GltfModel model = new GltfModel();
        readImages(model);
        readMaterials(model);
        readMeshes(model);
        readNodes(model);
        readAnimations(model);
        model.index();
        return model;
    }

    // ---- sections -------------------------------------------------------------------------------

    private void readImages(GltfModel model) throws IOException {
        for (JsonElement e : array(json, "images")) {
            JsonObject image = e.getAsJsonObject();
            if (!image.has("bufferView")) throw new IOException("only embedded images are supported");
            ByteBuffer view = bufferView(image.get("bufferView").getAsInt());
            byte[] bytes = new byte[view.remaining()];
            view.get(bytes);
            model.images.add(bytes);
        }
    }

    private void readMaterials(GltfModel model) {
        JsonArray textures = array(json, "textures");
        for (JsonElement e : array(json, "materials")) {
            JsonObject m = e.getAsJsonObject();
            GltfModel.Material material = new GltfModel.Material();
            material.name = string(m, "name", "");
            if (m.has("pbrMetallicRoughness")) {
                JsonObject pbr = m.getAsJsonObject("pbrMetallicRoughness");
                floats(pbr, "baseColorFactor", material.baseColor);
                material.metallic = number(pbr, "metallicFactor", 1);
                material.roughness = number(pbr, "roughnessFactor", 1);
                if (pbr.has("baseColorTexture")) {
                    int texture = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                    if (texture < textures.size()) {
                        JsonObject t = textures.get(texture).getAsJsonObject();
                        material.image = t.has("source") ? t.get("source").getAsInt() : -1;
                    }
                }
            }
            floats(m, "emissiveFactor", material.emissive);
            material.alphaMode = string(m, "alphaMode", "OPAQUE");
            material.alphaCutoff = number(m, "alphaCutoff", 0.5f);
            material.doubleSided = m.has("doubleSided") && m.get("doubleSided").getAsBoolean();
            if (m.has("extras") && m.get("extras").isJsonObject()) {
                // asset spec §20/§21: LabPBR texture set, skin region and tiling
                JsonObject extras = m.getAsJsonObject("extras");
                material.pbr = string(extras, "pbr", "").trim();
                material.region = string(extras, "region", "").trim();
                material.uvScale = number(extras, "uvScale", 1);
                if (!(material.uvScale > 0) || Float.isInfinite(material.uvScale)) material.uvScale = 1;
            }
            model.materials.add(material);
        }
    }

    private void readMeshes(GltfModel model) throws IOException {
        for (JsonElement e : array(json, "meshes")) {
            JsonObject m = e.getAsJsonObject();
            GltfModel.Mesh mesh = new GltfModel.Mesh();
            mesh.name = string(m, "name", "");
            for (JsonElement pe : array(m, "primitives")) {
                JsonObject p = pe.getAsJsonObject();
                int mode = p.has("mode") ? p.get("mode").getAsInt() : 4;
                if (mode != 4) throw new IOException("mesh " + mesh.name + ": only TRIANGLES are supported");
                JsonObject attributes = p.getAsJsonObject("attributes");
                GltfModel.Primitive primitive = new GltfModel.Primitive();
                primitive.positions = floatAccessor(attributes.get("POSITION").getAsInt());
                int vertices = primitive.positions.length / 3;
                primitive.normals = attributes.has("NORMAL") ? floatAccessor(attributes.get("NORMAL").getAsInt()) : null;
                primitive.uvs = attributes.has("TEXCOORD_0") ? floatAccessor(attributes.get("TEXCOORD_0").getAsInt()) : new float[vertices * 2];
                if (p.has("indices")) {
                    primitive.indices = intAccessor(p.get("indices").getAsInt());
                } else {
                    primitive.indices = new int[vertices];
                    for (int i = 0; i < vertices; i++) primitive.indices[i] = i;
                }
                if (primitive.normals == null) primitive.normals = flatNormals(primitive);
                if (attributes.has("COLOR_0")) primitive.ao = occlusion(attributes.get("COLOR_0").getAsInt(), vertices);
                if (attributes.has("JOINTS_0") && attributes.has("WEIGHTS_0")) {
                    float[] joints = floatAccessor(attributes.get("JOINTS_0").getAsInt());
                    float[] weights = floatAccessor(attributes.get("WEIGHTS_0").getAsInt());
                    if (joints.length == vertices * 4 && weights.length == vertices * 4) {
                        primitive.joints = new int[joints.length];
                        for (int i = 0; i < joints.length; i++) primitive.joints[i] = (int) joints[i];
                        primitive.weights = weights;
                    }
                }
                primitive.material = p.has("material") ? p.get("material").getAsInt() : -1;
                mesh.primitives.add(primitive);
            }
            model.meshes.add(mesh);
        }
    }

    private void readNodes(GltfModel model) {
        JsonArray nodes = array(json, "nodes");
        for (JsonElement e : nodes) {
            JsonObject n = e.getAsJsonObject();
            GltfModel.Node node = new GltfModel.Node();
            node.name = string(n, "name", "");
            node.mesh = n.has("mesh") ? n.get("mesh").getAsInt() : -1;
            floats(n, "translation", node.translation);
            floats(n, "rotation", node.rotation);
            floats(n, "scale", node.scale);
            if (n.has("extras") && n.get("extras").isJsonObject()) {
                JsonObject extras = n.getAsJsonObject("extras");
                for (var entry : extras.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) node.extras.put(entry.getKey(), entry.getValue().getAsString());
                }
                // a skinned arms mesh (asset spec §7): its joints and their bind translations in the anchor's space
                if (extras.has("skin") && extras.get("skin").isJsonArray()) {
                    JsonArray skin = extras.getAsJsonArray("skin");
                    JsonArray bind = array(extras, "bind");
                    node.skin = new String[skin.size()];
                    node.bind = new float[skin.size() * 3];
                    for (int i = 0; i < skin.size(); i++) {
                        node.skin[i] = skin.get(i).getAsString();
                        if (i < bind.size() && bind.get(i).isJsonArray()) {
                            JsonArray b = bind.get(i).getAsJsonArray();
                            for (int c = 0; c < 3 && c < b.size(); c++) node.bind[i * 3 + c] = b.get(c).getAsFloat();
                        }
                    }
                }
            }
            JsonArray children = array(n, "children");
            node.children = new int[children.size()];
            for (int i = 0; i < children.size(); i++) node.children[i] = children.get(i).getAsInt();
            model.nodes.add(node);
        }
        for (int i = 0; i < model.nodes.size(); i++) {
            for (int child : model.nodes.get(i).children) model.nodes.get(child).parent = i;
        }
    }

    private void readAnimations(GltfModel model) throws IOException {
        for (JsonElement e : array(json, "animations")) {
            JsonObject a = e.getAsJsonObject();
            GltfModel.Animation animation = new GltfModel.Animation();
            animation.name = string(a, "name", "");
            JsonArray samplers = array(a, "samplers");
            for (JsonElement ce : array(a, "channels")) {
                JsonObject c = ce.getAsJsonObject();
                JsonObject target = c.getAsJsonObject("target");
                if (!target.has("node")) continue;
                GltfModel.Path path = switch (target.get("path").getAsString()) {
                    case "translation" -> GltfModel.Path.TRANSLATION;
                    case "rotation" -> GltfModel.Path.ROTATION;
                    case "scale" -> GltfModel.Path.SCALE;
                    default -> null; // morph weights are not supported
                };
                if (path == null) continue;
                JsonObject sampler = samplers.get(c.get("sampler").getAsInt()).getAsJsonObject();
                String interpolation = string(sampler, "interpolation", "LINEAR");
                GltfModel.Channel channel = new GltfModel.Channel();
                channel.node = target.get("node").getAsInt();
                channel.path = path;
                channel.times = floatAccessor(sampler.get("input").getAsInt());
                channel.values = floatAccessor(sampler.get("output").getAsInt());
                channel.step = "STEP".equals(interpolation);
                channel.cubic = "CUBICSPLINE".equals(interpolation);
                int width = path == GltfModel.Path.ROTATION ? 4 : 3;
                if (channel.values.length != channel.times.length * width * (channel.cubic ? 3 : 1)) {
                    throw new IOException("animation " + animation.name + ": output size does not match its keys");
                }
                if (channel.times.length > 0) animation.duration = Math.max(animation.duration, channel.times[channel.times.length - 1]);
                animation.channels.add(channel);
            }
            if (a.has("extras") && a.getAsJsonObject("extras").has("events")) {
                for (JsonElement ev : a.getAsJsonObject("extras").getAsJsonArray("events")) {
                    JsonObject event = ev.getAsJsonObject();
                    animation.events.add(new GltfModel.Event(event.get("time").getAsFloat(), event.get("name").getAsString()));
                }
                animation.events.sort((x, y) -> Float.compare(x.time(), y.time()));
            }
            model.animations.add(animation);
        }
    }

    // ---- accessors ------------------------------------------------------------------------------

    private ByteBuffer bufferView(int index) throws IOException {
        JsonObject view = array(json, "bufferViews").get(index).getAsJsonObject();
        int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
        int length = view.get("byteLength").getAsInt();
        if (offset + length > bin.capacity()) throw new IOException("bufferView " + index + " out of range");
        return bin.duplicate().position(offset).limit(offset + length).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private record Accessor(ByteBuffer data, int componentType, int components, int count, int stride, boolean normalized) {
    }

    private Accessor accessor(int index) throws IOException {
        JsonObject a = array(json, "accessors").get(index).getAsJsonObject();
        if (a.has("sparse")) throw new IOException("sparse accessors are not supported");
        int components = switch (a.get("type").getAsString()) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new IOException("unsupported accessor type " + a.get("type"));
        };
        int componentType = a.get("componentType").getAsInt();
        int count = a.get("count").getAsInt();
        int viewIndex = a.get("bufferView").getAsInt();
        JsonObject view = array(json, "bufferViews").get(viewIndex).getAsJsonObject();
        int componentSize = switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IOException("unsupported component type " + componentType);
        };
        int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : componentSize * components;
        ByteBuffer data = bufferView(viewIndex);
        int offset = a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0;
        if (count > 0 && offset + (long) stride * (count - 1) + (long) componentSize * components > data.capacity()) {
            throw new IOException("accessor " + index + " out of range");
        }
        data.position(offset);
        return new Accessor(data.slice().order(ByteOrder.LITTLE_ENDIAN), componentType, components, count, stride,
                a.has("normalized") && a.get("normalized").getAsBoolean());
    }

    private float[] floatAccessor(int index) throws IOException {
        Accessor a = accessor(index);
        float[] out = new float[a.count * a.components];
        for (int i = 0; i < a.count; i++) {
            int base = i * a.stride;
            for (int c = 0; c < a.components; c++) out[i * a.components + c] = component(a, base, c);
        }
        return out;
    }

    private static float component(Accessor a, int base, int c) {
        return switch (a.componentType) {
            case 5126 -> a.data.getFloat(base + c * 4);
            case 5121 -> (a.data.get(base + c) & 0xFF) / (a.normalized ? 255f : 1f);
            case 5120 -> a.normalized ? Math.max(a.data.get(base + c) / 127f, -1f) : a.data.get(base + c);
            case 5123 -> (a.data.getShort(base + c * 2) & 0xFFFF) / (a.normalized ? 65535f : 1f);
            case 5122 -> a.normalized ? Math.max(a.data.getShort(base + c * 2) / 32767f, -1f) : a.data.getShort(base + c * 2);
            case 5125 -> (float) (a.data.getInt(base + c * 4) & 0xFFFFFFFFL);
            default -> 0;
        };
    }

    /** Baked AO from a COLOR_0 accessor (VEC3 / VEC4, float or normalized integers): its red channel, clamped. */
    private float[] occlusion(int index, int vertices) throws IOException {
        Accessor a = accessor(index);
        if (a.count != vertices || a.components < 3) return null;
        float[] ao = new float[vertices];
        for (int i = 0; i < vertices; i++) ao[i] = Math.max(0, Math.min(1, component(a, i * a.stride, 0)));
        return ao;
    }

    private int[] intAccessor(int index) throws IOException {
        Accessor a = accessor(index);
        int[] out = new int[a.count];
        for (int i = 0; i < a.count; i++) {
            int base = i * a.stride;
            out[i] = switch (a.componentType) {
                case 5121 -> a.data.get(base) & 0xFF;
                case 5123 -> a.data.getShort(base) & 0xFFFF;
                case 5125 -> a.data.getInt(base);
                default -> throw new IOException("unsupported index type " + a.componentType);
            };
        }
        return out;
    }

    private static float[] flatNormals(GltfModel.Primitive p) {
        float[] n = new float[p.positions.length];
        for (int t = 0; t + 2 < p.indices.length; t += 3) {
            int a = p.indices[t] * 3, b = p.indices[t + 1] * 3, c = p.indices[t + 2] * 3;
            float ux = p.positions[b] - p.positions[a], uy = p.positions[b + 1] - p.positions[a + 1], uz = p.positions[b + 2] - p.positions[a + 2];
            float vx = p.positions[c] - p.positions[a], vy = p.positions[c + 1] - p.positions[a + 1], vz = p.positions[c + 2] - p.positions[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int v : new int[]{a, b, c}) {
                n[v] += nx;
                n[v + 1] += ny;
                n[v + 2] += nz;
            }
        }
        for (int i = 0; i < n.length; i += 3) {
            float len = (float) Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
            if (len > 1e-6f) {
                n[i] /= len;
                n[i + 1] /= len;
                n[i + 2] /= len;
            } else {
                n[i + 1] = 1;
            }
        }
        return n;
    }

    // ---- json helpers ---------------------------------------------------------------------------

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static float number(JsonObject o, String key, float fallback) {
        return o.has(key) ? o.get(key).getAsFloat() : fallback;
    }

    private static void floats(JsonObject o, String key, float[] into) {
        if (!o.has(key)) return;
        JsonArray a = o.getAsJsonArray(key);
        for (int i = 0; i < into.length && i < a.size(); i++) into[i] = a.get(i).getAsFloat();
    }
}
