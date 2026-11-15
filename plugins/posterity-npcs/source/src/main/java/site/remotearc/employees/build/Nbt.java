package site.remotearc.employees.build;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A minimal reader for Minecraft's NBT format (gzipped or not), enough for
 * schematic files: compounds become Maps, lists become Lists, arrays stay
 * arrays. Bounded so a corrupt or hostile file can't exhaust memory.
 */
final class Nbt {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_ARRAY = 64 * 1024 * 1024;

    private Nbt() {}

    /** Writes a gzipped NBT file: Maps are compounds; Byte, Short, Integer, Long, Float, Double, byte[], String, List, int[]. */
    static void write(java.io.OutputStream raw, String rootName, Map<String, Object> root) throws IOException {
        java.io.DataOutputStream d = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(raw), 65536));
        d.writeByte(10);
        d.writeUTF(rootName);
        writePayload(d, root);
        d.close();
    }

    private static int typeOf(Object v) {
        return switch (v) {
            case Byte b -> 1;
            case Short sh -> 2;
            case Integer i -> 3;
            case Long l -> 4;
            case Float f -> 5;
            case Double dd -> 6;
            case byte[] arr -> 7;
            case String str -> 8;
            case List<?> list -> 9;
            case Map<?, ?> map -> 10;
            case int[] arr -> 11;
            default -> throw new IllegalArgumentException("can't write " + v.getClass());
        };
    }

    private static void writePayload(java.io.DataOutputStream d, Object v) throws IOException {
        switch (v) {
            case Byte b -> d.writeByte(b);
            case Short sh -> d.writeShort(sh);
            case Integer i -> d.writeInt(i);
            case Long l -> d.writeLong(l);
            case Float f -> d.writeFloat(f);
            case Double dd -> d.writeDouble(dd);
            case byte[] arr -> {
                d.writeInt(arr.length);
                d.write(arr);
            }
            case String str -> d.writeUTF(str);
            case List<?> list -> {
                d.writeByte(list.isEmpty() ? 0 : typeOf(list.getFirst()));
                d.writeInt(list.size());
                for (Object o : list) writePayload(d, o);
            }
            case Map<?, ?> map -> {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    d.writeByte(typeOf(e.getValue()));
                    d.writeUTF(String.valueOf(e.getKey()));
                    writePayload(d, e.getValue());
                }
                d.writeByte(0);
            }
            case int[] arr -> {
                d.writeInt(arr.length);
                for (int i : arr) d.writeInt(i);
            }
            default -> throw new IllegalArgumentException("can't write " + v.getClass());
        }
    }

    /** Reads the root compound. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int a = in.read(), b = in.read();
        in.reset();
        InputStream src = a == 0x1f && b == 0x8b ? new GZIPInputStream(in, 65536) : in;
        DataInputStream d = new DataInputStream(new BufferedInputStream(src, 65536));
        int type = d.readUnsignedByte();
        if (type != 10) throw new IOException("not an NBT file (root tag type " + type + ")");
        d.readUTF();
        return (Map<String, Object>) payload(d, 10, 0);
    }

    private static Object payload(DataInputStream d, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        return switch (type) {
            case 1 -> d.readByte();
            case 2 -> d.readShort();
            case 3 -> d.readInt();
            case 4 -> d.readLong();
            case 5 -> d.readFloat();
            case 6 -> d.readDouble();
            case 7 -> {
                byte[] arr = new byte[length(d)];
                d.readFully(arr);
                yield arr;
            }
            case 8 -> d.readUTF();
            case 9 -> {
                int inner = d.readUnsignedByte();
                int n = length(d);
                List<Object> list = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) list.add(payload(d, inner, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = d.readUnsignedByte();
                    if (t == 0) break;
                    String name = d.readUTF();
                    map.put(name, payload(d, t, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int[] arr = new int[length(d)];
                for (int i = 0; i < arr.length; i++) arr[i] = d.readInt();
                yield arr;
            }
            case 12 -> {
                long[] arr = new long[length(d)];
                for (int i = 0; i < arr.length; i++) arr[i] = d.readLong();
                yield arr;
            }
            default -> throw new IOException("unknown NBT tag type " + type);
        };
    }

    private static int length(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > MAX_ARRAY) throw new IOException("NBT array length " + n + " out of range");
        return n;
    }

    // ── typed access ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Map<String, Object> m, String key) {
        return m.get(key) instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String key) {
        return m.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    static int integer(Map<String, Object> m, String key, int def) {
        return m.get(key) instanceof Number n ? n.intValue() : def;
    }

    static String string(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? s : null;
    }
}
