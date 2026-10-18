package fr.plexwish.animeserver.live;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MP4 fragmenté écrit par ffmpeg ({@code -movflags +frag_keyframe+empty_moov+default_base_moof+separate_moof}),
 * docs/LIVE-REMUX.md §3.2-3.3. Utilitaire sans état : lecture des boîtes, piste et horodatage d'un fragment,
 * correction de l'horodatage ({@code tfdt}), en-tête réduit à une piste. Rien d'autre n'est interprété.
 */
public final class Fmp4 {

    /** Plus grande boîte acceptée (un fragment = une image clé et ce qui la suit : quelques Mo). */
    static final long MAX_BOX = 256L * 1024 * 1024;

    private Fmp4() {
    }

    /** Boîte de premier niveau : type (4 lettres) et octets complets (en-tête compris). */
    public record Box(String type, byte[] bytes) {
    }

    /** Piste de l'en-tête : identifiant, type ({@code vide}, {@code soun}…) et échelle de temps (unités par seconde). */
    public record Track(int id, String handler, long timescale) {
    }

    // --- Lecture d'un flux --------------------------------------------------------------------------------------------

    /** Boîte suivante du flux, ou null à la fin (fin propre entre deux boîtes). */
    public static Box next(InputStream in) throws IOException {
        byte[] head = new byte[8];
        int n = in.readNBytes(head, 0, 8);
        if (n == 0) {
            return null;
        }
        if (n < 8) {
            throw new EOFException("boîte tronquée");
        }
        long size = Integer.toUnsignedLong(ByteBuffer.wrap(head, 0, 4).getInt());
        String type = new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] large = null;
        if (size == 1) {
            large = in.readNBytes(8);
            if (large.length < 8) {
                throw new EOFException("boîte tronquée");
            }
            size = ByteBuffer.wrap(large).getLong();
        }
        int headerLength = large == null ? 8 : 16;
        if (size < headerLength || size > MAX_BOX) {
            throw new IOException("taille de boîte invalide : " + size);
        }
        byte[] bytes = new byte[(int) size];
        System.arraycopy(head, 0, bytes, 0, 8);
        if (large != null) {
            System.arraycopy(large, 0, bytes, 8, 8);
        }
        int body = (int) size - headerLength;
        if (in.readNBytes(bytes, headerLength, body) < body) {
            throw new EOFException("boîte tronquée");
        }
        return new Box(type, bytes);
    }

    // --- Parcours d'un tableau d'octets ------------------------------------------------------------------------------

    /** Boîte trouvée dans un tableau : type, début, taille, début du contenu. */
    record Child(String type, int offset, int size, int body) {
    }

    static List<Child> children(byte[] b, int from, int to) {
        List<Child> out = new ArrayList<>();
        int off = from;
        while (off + 8 <= to) {
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(b, off, 4).getInt());
            String type = new String(b, off + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int body = off + 8;
            if (size == 1) {
                size = ByteBuffer.wrap(b, off + 8, 8).getLong();
                body = off + 16;
            }
            if (size < 8 || off + size > to) {
                break;
            }
            out.add(new Child(type, off, (int) size, body));
            off += (int) size;
        }
        return out;
    }

    static Child first(byte[] b, Child parent, String type) {
        for (Child c : children(b, parent.body(), parent.offset() + parent.size())) {
            if (c.type().equals(type)) {
                return c;
            }
        }
        return null;
    }

    private static Child tfdtOf(byte[] moof, Child[] tfhdOut) {
        List<Child> top = children(moof, 0, moof.length);
        if (top.isEmpty() || !top.get(0).type().equals("moof")) {
            throw new IllegalArgumentException("pas un moof");
        }
        Child traf = first(moof, top.get(0), "traf");
        if (traf == null) {
            throw new IllegalArgumentException("moof sans traf");
        }
        if (tfhdOut != null) {
            tfhdOut[0] = first(moof, traf, "tfhd");
        }
        Child tfdt = first(moof, traf, "tfdt");
        if (tfdt == null) {
            throw new IllegalArgumentException("traf sans tfdt");
        }
        return tfdt;
    }

    /** Piste d'un fragment ({@code moof} avec une seule {@code traf}, comme l'écrit {@code separate_moof}). */
    public static int trackId(byte[] moof) {
        Child[] tfhd = new Child[1];
        tfdtOf(moof, tfhd);
        if (tfhd[0] == null) {
            throw new IllegalArgumentException("traf sans tfhd");
        }
        return ByteBuffer.wrap(moof, tfhd[0].body() + 4, 4).getInt();
    }

    /** Début du fragment (temps de décodage, unités de la piste). */
    public static long decodeTime(byte[] moof) {
        Child t = tfdtOf(moof, null);
        int version = moof[t.body()] & 0xFF;
        return version == 1 ? ByteBuffer.wrap(moof, t.body() + 4, 8).getLong()
                : Integer.toUnsignedLong(ByteBuffer.wrap(moof, t.body() + 4, 4).getInt());
    }

    /**
     * Ajoute {@code delta} (unités de la piste) au début du fragment, sur place, sans changer de taille : ffmpeg écrit
     * {@code tfdt} en version 1 (64 bits) ; une version 0 n'est acceptée que si la valeur tient sur 32 bits.
     */
    public static void shiftDecodeTime(byte[] moof, long delta) {
        Child t = tfdtOf(moof, null);
        int version = moof[t.body()] & 0xFF;
        long value = decodeTime(moof) + delta;
        if (value < 0) {
            throw new IllegalArgumentException("horodatage négatif");
        }
        if (version == 1) {
            ByteBuffer.wrap(moof, t.body() + 4, 8).putLong(value);
        } else {
            if (value > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("tfdt version 0 trop petit");
            }
            ByteBuffer.wrap(moof, t.body() + 4, 4).putInt((int) value);
        }
    }

    // --- En-tête (ftyp + moov) ---------------------------------------------------------------------------------------

    /** Pistes décrites par l'en-tête, dans l'ordre. */
    public static List<Track> tracks(byte[] header) {
        List<Track> out = new ArrayList<>();
        for (Child moov : children(header, 0, header.length)) {
            if (!moov.type().equals("moov")) {
                continue;
            }
            for (Child trak : children(header, moov.body(), moov.offset() + moov.size())) {
                if (!trak.type().equals("trak")) {
                    continue;
                }
                out.add(track(header, trak));
            }
        }
        return out;
    }

    private static Track track(byte[] b, Child trak) {
        Child tkhd = first(b, trak, "tkhd");
        Child mdia = first(b, trak, "mdia");
        if (tkhd == null || mdia == null) {
            throw new IllegalArgumentException("trak incomplet");
        }
        int v = b[tkhd.body()] & 0xFF;
        int id = ByteBuffer.wrap(b, tkhd.body() + 4 + (v == 1 ? 16 : 8), 4).getInt();
        Child mdhd = first(b, mdia, "mdhd");
        Child hdlr = first(b, mdia, "hdlr");
        if (mdhd == null || hdlr == null) {
            throw new IllegalArgumentException("mdia incomplet");
        }
        int mv = b[mdhd.body()] & 0xFF;
        long timescale = Integer.toUnsignedLong(ByteBuffer.wrap(b, mdhd.body() + 4 + (mv == 1 ? 16 : 8), 4).getInt());
        String handler = new String(b, hdlr.body() + 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        return new Track(id, handler, timescale);
    }

    /**
     * En-tête réduit à une piste ({@code trackId}) : {@code ftyp}, puis {@code moov} sans les autres {@code trak} ni
     * leurs {@code trex}. Sert de {@code EXT-X-MAP} à la playlist de cette piste (§3.3).
     */
    public static byte[] trackHeader(byte[] header, int trackId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean found = false;
        for (Child top : children(header, 0, header.length)) {
            if (top.type().equals("ftyp")) {
                out.write(header, top.offset(), top.size());
            } else if (top.type().equals("moov")) {
                ByteArrayOutputStream moov = new ByteArrayOutputStream();
                for (Child c : children(header, top.body(), top.offset() + top.size())) {
                    if (c.type().equals("trak")) {
                        if (track(header, c).id() == trackId) {
                            moov.write(header, c.offset(), c.size());
                            found = true;
                        }
                    } else if (c.type().equals("mvex")) {
                        ByteArrayOutputStream mvex = new ByteArrayOutputStream();
                        for (Child m : children(header, c.body(), c.offset() + c.size())) {
                            if (m.type().equals("trex") && ByteBuffer.wrap(header, m.body() + 4, 4).getInt() != trackId) {
                                continue;
                            }
                            mvex.write(header, m.offset(), m.size());
                        }
                        writeBox(moov, "mvex", mvex.toByteArray());
                    } else {
                        moov.write(header, c.offset(), c.size());
                    }
                }
                writeBox(out, "moov", moov.toByteArray());
            }
        }
        if (!found) {
            throw new IllegalArgumentException("piste absente de l'en-tête : " + trackId);
        }
        return out.toByteArray();
    }

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] body) {
        ByteBuffer h = ByteBuffer.allocate(8).putInt(8 + body.length).put(type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.write(h.array(), 0, 8);
        out.write(body, 0, body.length);
    }

    /** Échelle de temps par piste. */
    public static Map<Integer, Long> timescales(byte[] header) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (Track t : tracks(header)) {
            out.put(t.id(), t.timescale());
        }
        return out;
    }
}
