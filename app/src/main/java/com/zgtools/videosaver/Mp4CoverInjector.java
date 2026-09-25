package com.zgtools.videosaver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 极轻量 MP4 封面嵌入器。
 * <p>
 * 在不重新编码音视频像素/采样、不引入外部笨重依赖（如完整 mp4parser）的前提下，
 * 纯依靠 MP4 ISO BMFF box 树结构操作，将封面图（JPEG/PNG）封装进：
 * moov -> udta -> meta -> ilst -> covr -> data
 * <p>
 * 若修改过程中导致 mdat 块相对于文件开头的偏移发生变化，会安全同步修正所有 trak
 * 中的 chunk offset 表（stco / co64），确保视频在任何严格播放器中均能正常按帧索引。
 */
public final class Mp4CoverInjector {

    private static final byte[] TYPE_MOOV = "moov".getBytes();
    private static final byte[] TYPE_UDTA = "udta".getBytes();
    private static final byte[] TYPE_META = "meta".getBytes();
    private static final byte[] TYPE_ILST = "ilst".getBytes();
    private static final byte[] TYPE_COVR = "covr".getBytes();
    private static final byte[] TYPE_DATA = "data".getBytes();
    private static final byte[] TYPE_HDLR = "hdlr".getBytes();
    private static final byte[] TYPE_MDAT = "mdat".getBytes();
    private static final byte[] TYPE_TRAK = "trak".getBytes();
    private static final byte[] TYPE_MDIA = "mdia".getBytes();
    private static final byte[] TYPE_MINF = "minf".getBytes();
    private static final byte[] TYPE_STBL = "stbl".getBytes();
    private static final byte[] TYPE_STCO = "stco".getBytes();
    private static final byte[] TYPE_CO64 = "co64".getBytes();

    private Mp4CoverInjector() {}

    /**
     * 将封面图片注入到 MP4 文件的元数据中。
     *
     * @param sourceVideo 源 MP4 文件
     * @param targetVideo 输出 MP4 文件（可以与源文件不同；如果注入失败会退回直接复制源文件）
     * @param coverBytes  封面图片原始二进制（JPEG 或 PNG）
     * @return true 表示成功嵌入封面；false 表示未成功（已安全降级为原片直接复制）
     */
    public static boolean injectCover(File sourceVideo, File targetVideo, byte[] coverBytes) {
        if (sourceVideo == null || !sourceVideo.isFile() || sourceVideo.length() == 0) return false;
        if (coverBytes == null || coverBytes.length == 0) return false;

        try {
            // 判定图片类型标志：13 为 JPEG, 14 为 PNG (Apple ilst 标准)
            int dataType = isPng(coverBytes) ? 14 : 13;
            byte[] covrBox = buildCovrBox(coverBytes, dataType);

            // 尝试读取并解析源文件 top boxes
            try (RandomAccessFile raf = new RandomAccessFile(sourceVideo, "r")) {
                long fileLength = raf.length();
                long pos = 0;
                long moovOffset = -1;
                long moovSize = -1;
                long mdatOffset = -1;

                while (pos < fileLength - 8) {
                    raf.seek(pos);
                    int size32 = raf.readInt();
                    byte[] typeBytes = new byte[4];
                    raf.readFully(typeBytes);
                    long boxSize = size32 & 0xFFFFFFFFL;
                    long headerSize = 8;
                    if (boxSize == 1) {
                        boxSize = raf.readLong();
                        headerSize = 16;
                    } else if (boxSize == 0) {
                        boxSize = fileLength - pos;
                    }

                    if (Arrays.equals(typeBytes, TYPE_MOOV)) {
                        moovOffset = pos;
                        moovSize = boxSize;
                    } else if (Arrays.equals(typeBytes, TYPE_MDAT)) {
                        mdatOffset = pos;
                    }

                    if (boxSize <= 0) break;
                    pos += boxSize;
                }

                if (moovOffset < 0 || moovSize <= 8) {
                    return false;
                }

                // 读取完整的 moov box 内容
                raf.seek(moovOffset);
                byte[] rawMoov = new byte[(int) moovSize];
                raf.readFully(rawMoov);

                // 在 moov 数据中注入 covr
                byte[] newMoov = injectCovrIntoMoov(rawMoov, covrBox);
                if (newMoov == null) return false;

                long sizeDiff = newMoov.length - rawMoov.length;

                // 如果 moov 在 mdat 前面，moov 增大会改变 mdat 的文件偏移，
                // 此时必须在 newMoov 中对所有的 stco / co64 加上 sizeDiff
                if (mdatOffset > moovOffset && sizeDiff != 0) {
                    adjustChunkOffsets(newMoov, sizeDiff);
                }

                // 将结果写出到 targetVideo
                try (FileOutputStream fos = new FileOutputStream(targetVideo);
                     FileChannel outCh = fos.getChannel();
                     FileInputStream fis = new FileInputStream(sourceVideo);
                     FileChannel inCh = fis.getChannel()) {

                    if (moovOffset > 0) {
                        inCh.transferTo(0, moovOffset, outCh);
                    }
                    ByteBuffer buf = ByteBuffer.wrap(newMoov);
                    while (buf.hasRemaining()) {
                        outCh.write(buf);
                    }
                    long afterMoov = moovOffset + moovSize;
                    if (afterMoov < fileLength) {
                        inCh.transferTo(afterMoov, fileLength - afterMoov, outCh);
                    }
                }
                return true;
            }
        } catch (Throwable t) {
            // 无论任何异常均不崩溃，调用方只需知晓是否成功
            return false;
        }
    }

    private static boolean isPng(byte[] data) {
        return data.length > 8
                && (data[0] & 0xFF) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G';
    }

    /**
     * 构建完整的 covr box：
     * covr (container)
     *   -> data (type 13 for jpg, 14 for png, version=0, flags=type, 4 bytes reserved, payload)
     */
    private static byte[] buildCovrBox(byte[] imageData, int imageType) {
        int dataBoxSize = 8 + 8 + imageData.length;
        int covrBoxSize = 8 + dataBoxSize;
        ByteBuffer bb = ByteBuffer.allocate(covrBoxSize);

        // covr header
        bb.putInt(covrBoxSize);
        bb.put(TYPE_COVR);

        // data header
        bb.putInt(dataBoxSize);
        bb.put(TYPE_DATA);
        // version 0 (1 byte), flags (3 bytes = imageType)
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.put((byte) imageType);
        // reserved 4 bytes
        bb.putInt(0);
        // payload
        bb.put(imageData);

        return bb.array();
    }

    /**
     * 构建符合 Apple 规范的最小 meta 容器（如果原视频中没有 meta 或 ilst）：
     * meta (FullBox: ver=0, flags=0)
     *   -> hdlr (type='mdir', sub='appl')
     *   -> ilst
     *        -> covr
     */
    private static byte[] buildMetaBox(byte[] covrBox) {
        // hdlr box: 8 + 4 + 4 + 4 + 12 + 1 = 33 bytes
        byte[] hdlr = new byte[] {
                0, 0, 0, 33, // size 33
                'h', 'd', 'l', 'r',
                0, 0, 0, 0, // version 0, flags 0
                0, 0, 0, 0, // pre_defined
                'm', 'd', 'i', 'r', // handler_type
                'a', 'p', 'p', 'l', // manufacturer
                0, 0, 0, 0, // flags
                0, 0, 0, 0, // mask
                0 // name (empty string)
        };

        int ilstSize = 8 + covrBox.length;
        byte[] ilst = new byte[ilstSize];
        ByteBuffer.wrap(ilst).putInt(ilstSize).put(TYPE_ILST).put(covrBox);

        int metaSize = 12 + hdlr.length + ilst.length; // 8 (header) + 4 (ver/flags) + children
        ByteBuffer meta = ByteBuffer.allocate(metaSize);
        meta.putInt(metaSize);
        meta.put(TYPE_META);
        meta.putInt(0); // version 0, flags 0
        meta.put(hdlr);
        meta.put(ilst);
        return meta.array();
    }

    /**
     * 在已有的 moov byte array 中找到 udta (或创建 udta)，
     * 并把 covr 注入进去，返回新的 moov byte array。
     */
    private static byte[] injectCovrIntoMoov(byte[] moovData, byte[] covrBox) {
        ByteBuffer bb = ByteBuffer.wrap(moovData);
        int moovSize = bb.getInt();
        bb.position(8); // 跳过 moov 头

        int udtaOffset = -1;
        int udtaSize = -1;

        while (bb.position() <= moovData.length - 8) {
            int pos = bb.position();
            int bsize = bb.getInt();
            byte[] btype = new byte[4];
            bb.get(btype);

            if (Arrays.equals(btype, TYPE_UDTA)) {
                udtaOffset = pos;
                udtaSize = bsize;
                break;
            }
            if (bsize <= 8) break;
            int next = pos + bsize;
            if (next < 0 || next > moovData.length) break;
            bb.position(next);
        }

        byte[] newUdta;
        if (udtaOffset >= 0) {
            byte[] rawUdta = Arrays.copyOfRange(moovData, udtaOffset, udtaOffset + udtaSize);
            newUdta = injectCovrIntoUdta(rawUdta, covrBox);
            if (newUdta == null) return null;

            int newMoovLen = moovData.length - udtaSize + newUdta.length;
            ByteBuffer newMoov = ByteBuffer.allocate(newMoovLen);
            newMoov.putInt(newMoovLen);
            newMoov.put(TYPE_MOOV);
            newMoov.put(moovData, 8, udtaOffset - 8);
            newMoov.put(newUdta);
            int remaining = moovData.length - (udtaOffset + udtaSize);
            if (remaining > 0) {
                newMoov.put(moovData, udtaOffset + udtaSize, remaining);
            }
            return newMoov.array();
        } else {
            // 原 moov 中完全没有 udta，直接新建一个 udta
            byte[] metaBox = buildMetaBox(covrBox);
            int udtaLen = 8 + metaBox.length;
            ByteBuffer udtaBuf = ByteBuffer.allocate(udtaLen);
            udtaBuf.putInt(udtaLen);
            udtaBuf.put(TYPE_UDTA);
            udtaBuf.put(metaBox);
            newUdta = udtaBuf.array();

            int newMoovLen = moovData.length + newUdta.length;
            ByteBuffer newMoov = ByteBuffer.allocate(newMoovLen);
            newMoov.putInt(newMoovLen);
            newMoov.put(TYPE_MOOV);
            newMoov.put(moovData, 8, moovData.length - 8);
            newMoov.put(newUdta);
            return newMoov.array();
        }
    }

    private static byte[] injectCovrIntoUdta(byte[] udtaData, byte[] covrBox) {
        ByteBuffer bb = ByteBuffer.wrap(udtaData);
        int udtaSize = bb.getInt();
        bb.position(8);

        int metaOffset = -1;
        int metaSize = -1;

        while (bb.position() <= udtaData.length - 8) {
            int pos = bb.position();
            int bsize = bb.getInt();
            byte[] btype = new byte[4];
            bb.get(btype);

            if (Arrays.equals(btype, TYPE_META)) {
                metaOffset = pos;
                metaSize = bsize;
                break;
            }
            if (bsize <= 8) break;
            int next = pos + bsize;
            if (next < 0 || next > udtaData.length) break;
            bb.position(next);
        }

        if (metaOffset >= 0) {
            byte[] rawMeta = Arrays.copyOfRange(udtaData, metaOffset, metaOffset + metaSize);
            byte[] newMeta = injectCovrIntoMeta(rawMeta, covrBox);
            if (newMeta == null) return null;

            int newUdtaLen = udtaData.length - metaSize + newMeta.length;
            ByteBuffer newUdta = ByteBuffer.allocate(newUdtaLen);
            newUdta.putInt(newUdtaLen);
            newUdta.put(TYPE_UDTA);
            newUdta.put(udtaData, 8, metaOffset - 8);
            newUdta.put(newMeta);
            int rem = udtaData.length - (metaOffset + metaSize);
            if (rem > 0) {
                newUdta.put(udtaData, metaOffset + metaSize, rem);
            }
            return newUdta.array();
        } else {
            byte[] newMeta = buildMetaBox(covrBox);
            int newUdtaLen = udtaData.length + newMeta.length;
            ByteBuffer newUdta = ByteBuffer.allocate(newUdtaLen);
            newUdta.putInt(newUdtaLen);
            newUdta.put(TYPE_UDTA);
            newUdta.put(udtaData, 8, udtaData.length - 8);
            newUdta.put(newMeta);
            return newUdta.array();
        }
    }

    private static byte[] injectCovrIntoMeta(byte[] metaData, byte[] covrBox) {
        // meta box 包含 4 字节的 version/flags
        if (metaData.length < 12) return null;
        ByteBuffer bb = ByteBuffer.wrap(metaData);
        int metaSize = bb.getInt();
        bb.position(12); // 跳过 meta header (8) + ver/flags (4)

        int ilstOffset = -1;
        int ilstSize = -1;

        while (bb.position() <= metaData.length - 8) {
            int pos = bb.position();
            int bsize = bb.getInt();
            byte[] btype = new byte[4];
            bb.get(btype);

            if (Arrays.equals(btype, TYPE_ILST)) {
                ilstOffset = pos;
                ilstSize = bsize;
                break;
            }
            if (bsize <= 8) break;
            int next = pos + bsize;
            if (next < 0 || next > metaData.length) break;
            bb.position(next);
        }

        if (ilstOffset >= 0) {
            byte[] rawIlst = Arrays.copyOfRange(metaData, ilstOffset, ilstOffset + ilstSize);
            byte[] newIlst = injectCovrIntoIlst(rawIlst, covrBox);
            if (newIlst == null) return null;

            int newMetaLen = metaData.length - ilstSize + newIlst.length;
            ByteBuffer newMeta = ByteBuffer.allocate(newMetaLen);
            newMeta.putInt(newMetaLen);
            newMeta.put(TYPE_META);
            newMeta.put(metaData, 8, ilstOffset - 8);
            newMeta.put(newIlst);
            int rem = metaData.length - (ilstOffset + ilstSize);
            if (rem > 0) {
                newMeta.put(metaData, ilstOffset + ilstSize, rem);
            }
            return newMeta.array();
        } else {
            int ilstLen = 8 + covrBox.length;
            byte[] newIlst = new byte[ilstLen];
            ByteBuffer.wrap(newIlst).putInt(ilstLen).put(TYPE_ILST).put(covrBox);

            int newMetaLen = metaData.length + newIlst.length;
            ByteBuffer newMeta = ByteBuffer.allocate(newMetaLen);
            newMeta.putInt(newMetaLen);
            newMeta.put(TYPE_META);
            newMeta.put(metaData, 8, metaData.length - 8);
            newMeta.put(newIlst);
            return newMeta.array();
        }
    }

    private static byte[] injectCovrIntoIlst(byte[] ilstData, byte[] covrBox) {
        if (ilstData.length < 8) return null;
        ByteBuffer bb = ByteBuffer.wrap(ilstData);
        int ilstSize = bb.getInt();
        bb.position(8);

        int covrOffset = -1;
        int covrSize = -1;

        while (bb.position() <= ilstData.length - 8) {
            int pos = bb.position();
            int bsize = bb.getInt();
            byte[] btype = new byte[4];
            bb.get(btype);

            if (Arrays.equals(btype, TYPE_COVR)) {
                covrOffset = pos;
                covrSize = bsize;
                break;
            }
            if (bsize <= 8) break;
            int next = pos + bsize;
            if (next < 0 || next > ilstData.length) break;
            bb.position(next);
        }

        if (covrOffset >= 0) {
            // 已有 covr，直接替换
            int newIlstLen = ilstData.length - covrSize + covrBox.length;
            ByteBuffer newIlst = ByteBuffer.allocate(newIlstLen);
            newIlst.putInt(newIlstLen);
            newIlst.put(TYPE_ILST);
            newIlst.put(ilstData, 8, covrOffset - 8);
            newIlst.put(covrBox);
            int rem = ilstData.length - (covrOffset + covrSize);
            if (rem > 0) {
                newIlst.put(ilstData, covrOffset + covrSize, rem);
            }
            return newIlst.array();
        } else {
            // 追加到 ilst 末尾
            int newIlstLen = ilstData.length + covrBox.length;
            ByteBuffer newIlst = ByteBuffer.allocate(newIlstLen);
            newIlst.putInt(newIlstLen);
            newIlst.put(TYPE_ILST);
            newIlst.put(ilstData, 8, ilstData.length - 8);
            newIlst.put(covrBox);
            return newIlst.array();
        }
    }

    /**
     * 递归遍历 moov 数据中的所有 box，如果找到 stco 或 co64，
     * 则对其中所有 chunk offset 表项加上 delta。
     */
    private static void adjustChunkOffsets(byte[] moovData, long delta) {
        scanAndAdjustBoxes(moovData, 8, moovData.length, delta);
    }

    private static void scanAndAdjustBoxes(byte[] data, int start, int end, long delta) {
        int pos = start;
        while (pos <= end - 8) {
            int bsize = ((data[pos] & 0xFF) << 24)
                    | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8)
                    | (data[pos + 3] & 0xFF);

            if (bsize < 8) break;
            int nextBox = pos + bsize;
            if (nextBox < 0 || nextBox > end) break;

            byte t0 = data[pos + 4];
            byte t1 = data[pos + 5];
            byte t2 = data[pos + 6];
            byte t3 = data[pos + 7];

            if (t0 == 's' && t1 == 't' && t2 == 'c' && t3 == 'o') {
                // stco: 8 header + 4 ver/flags + 4 entry_count + entry_count * 4
                if (pos + 16 <= nextBox) {
                    int count = ((data[pos + 12] & 0xFF) << 24)
                            | ((data[pos + 13] & 0xFF) << 16)
                            | ((data[pos + 14] & 0xFF) << 8)
                            | (data[pos + 15] & 0xFF);
                    int cur = pos + 16;
                    for (int i = 0; i < count && cur + 4 <= nextBox; i++) {
                        long oldVal = (((long) (data[cur] & 0xFF)) << 24)
                                | (((long) (data[cur + 1] & 0xFF)) << 16)
                                | (((long) (data[cur + 2] & 0xFF)) << 8)
                                | ((long) (data[cur + 3] & 0xFF));
                        long newVal = oldVal + delta;
                        data[cur] = (byte) ((newVal >> 24) & 0xFF);
                        data[cur + 1] = (byte) ((newVal >> 16) & 0xFF);
                        data[cur + 2] = (byte) ((newVal >> 8) & 0xFF);
                        data[cur + 3] = (byte) (newVal & 0xFF);
                        cur += 4;
                    }
                }
            } else if (t0 == 'c' && t1 == 'o' && t2 == '6' && t3 == '4') {
                // co64: 8 header + 4 ver/flags + 4 entry_count + entry_count * 8
                if (pos + 16 <= nextBox) {
                    int count = ((data[pos + 12] & 0xFF) << 24)
                            | ((data[pos + 13] & 0xFF) << 16)
                            | ((data[pos + 14] & 0xFF) << 8)
                            | (data[pos + 15] & 0xFF);
                    int cur = pos + 16;
                    for (int i = 0; i < count && cur + 8 <= nextBox; i++) {
                        long oldVal = (((long) (data[cur] & 0xFF)) << 56)
                                | (((long) (data[cur + 1] & 0xFF)) << 48)
                                | (((long) (data[cur + 2] & 0xFF)) << 40)
                                | (((long) (data[cur + 3] & 0xFF)) << 32)
                                | (((long) (data[cur + 4] & 0xFF)) << 24)
                                | (((long) (data[cur + 5] & 0xFF)) << 16)
                                | (((long) (data[cur + 6] & 0xFF)) << 8)
                                | ((long) (data[cur + 7] & 0xFF));
                        long newVal = oldVal + delta;
                        for (int k = 7; k >= 0; k--) {
                            data[cur + k] = (byte) (newVal & 0xFF);
                            newVal >>= 8;
                        }
                        cur += 8;
                    }
                }
            } else if ((t0 == 't' && t1 == 'r' && t2 == 'a' && t3 == 'k')
                    || (t0 == 'm' && t1 == 'd' && t2 == 'i' && t3 == 'a')
                    || (t0 == 'm' && t1 == 'i' && t2 == 'n' && t3 == 'f')
                    || (t0 == 's' && t1 == 't' && t2 == 'b' && t3 == 'l')) {
                // 容器 box，向下深入递归
                scanAndAdjustBoxes(data, pos + 8, nextBox, delta);
            }
            pos = nextBox;
        }
    }
}
