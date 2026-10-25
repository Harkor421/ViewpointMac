package vpmac;

/** Internal formats to the format/type pair 2.1 wants when allocating, and their sizes. */
final class Formats {
    private Formats() {}

    static final int RED = 0x1903, RG = 0x8227, RGB = 0x1907, RGBA = 0x1908, DEPTH_COMPONENT = 0x1902, DEPTH_STENCIL = 0x84F9,
        RED_INTEGER = 0x8D94, RG_INTEGER = 0x8228, RGB_INTEGER = 0x8D98, RGBA_INTEGER = 0x8D99;
    static final int BYTE = 0x1400, UBYTE = 0x1401, SHORT = 0x1402, USHORT = 0x1403, INT = 0x1404, UINT = 0x1405,
        FLOAT = 0x1406, HALF = 0x140B, UINT_24_8 = 0x84FA, FLOAT_32_UINT_24_8_REV = 0x8DAD, UINT_2_10_10_10_REV = 0x8368;

    /** {format, type} for allocating {@code ifmt} without data. */
    static int[] formatType(int ifmt) {
        switch (ifmt) {
            case 0x8229: return new int[] {RED, UBYTE};          // R8
            case 0x822A: return new int[] {RED, USHORT};         // R16
            case 0x822B: return new int[] {RG, UBYTE};           // RG8
            case 0x822C: return new int[] {RG, USHORT};          // RG16
            case 0x822D: case 0x822E: return new int[] {RED, FLOAT};   // R16F, R32F
            case 0x822F: case 0x8230: return new int[] {RG, FLOAT};    // RG16F, RG32F
            case 0x8231: return new int[] {RED_INTEGER, BYTE};   // R8I
            case 0x8232: return new int[] {RED_INTEGER, UBYTE};  // R8UI
            case 0x8233: return new int[] {RED_INTEGER, SHORT};  // R16I
            case 0x8234: return new int[] {RED_INTEGER, USHORT}; // R16UI
            case 0x8235: return new int[] {RED_INTEGER, INT};    // R32I
            case 0x8236: return new int[] {RED_INTEGER, UINT};   // R32UI
            case 0x8237: return new int[] {RG_INTEGER, BYTE};
            case 0x8238: return new int[] {RG_INTEGER, UBYTE};
            case 0x8239: return new int[] {RG_INTEGER, SHORT};
            case 0x823A: return new int[] {RG_INTEGER, USHORT};
            case 0x823B: return new int[] {RG_INTEGER, INT};
            case 0x823C: return new int[] {RG_INTEGER, UINT};
            case 0x8814: case 0x881A: return new int[] {RGBA, FLOAT};  // RGBA32F, RGBA16F
            case 0x8815: case 0x881B: case 0x8C3A: case 0x8C3D: return new int[] {RGB, FLOAT}; // RGB32F, RGB16F, R11F_G11F_B10F, RGB9_E5
            case 0x8D70: return new int[] {RGBA_INTEGER, UINT};  // RGBA32UI
            case 0x8D71: return new int[] {RGB_INTEGER, UINT};
            case 0x8D76: return new int[] {RGBA_INTEGER, USHORT};
            case 0x8D7C: return new int[] {RGBA_INTEGER, UBYTE};
            case 0x8D82: return new int[] {RGBA_INTEGER, INT};
            case 0x8D88: return new int[] {RGBA_INTEGER, SHORT};
            case 0x8D8E: return new int[] {RGBA_INTEGER, BYTE};
            case 0x8059: return new int[] {RGBA, UINT_2_10_10_10_REV}; // RGB10_A2
            case 0x805B: return new int[] {RGBA, USHORT};        // RGBA16
            case 0x8051: case 0x8C41: return new int[] {RGB, UBYTE}; // RGB8, SRGB8
            case 0x81A5: return new int[] {DEPTH_COMPONENT, USHORT};
            case 0x81A6: case 0x81A7: return new int[] {DEPTH_COMPONENT, UINT};
            case 0x8CAC: return new int[] {DEPTH_COMPONENT, FLOAT};
            case 0x88F0: return new int[] {DEPTH_STENCIL, UINT_24_8};
            case 0x8CAD: return new int[] {DEPTH_STENCIL, FLOAT_32_UINT_24_8_REV};
            default: return new int[] {RGBA, UBYTE};             // RGBA8, SRGB8_ALPHA8, ...
        }
    }

    /** Bytes a texel of a buffer texture takes. */
    static int texelBytes(int ifmt) {
        int[] ft = formatType(ifmt);
        return components(ft[0]) * typeBytes(ft[1]);
    }

    static int pixelBytes(int format, int type) {
        if (type == UINT_24_8 || type == UINT_2_10_10_10_REV) return 4;
        if (type == FLOAT_32_UINT_24_8_REV) return 8;
        return components(format) * typeBytes(type);
    }

    static int components(int format) {
        switch (format) {
            case RED: case RED_INTEGER: case DEPTH_COMPONENT: return 1;
            case RG: case RG_INTEGER: case DEPTH_STENCIL: return 2;
            case RGB: case RGB_INTEGER: return 3;
            default: return 4;
        }
    }

    static int typeBytes(int type) {
        switch (type) {
            case BYTE: case UBYTE: return 1;
            case SHORT: case USHORT: case HALF: return 2;
            case 0x140A: return 8; // DOUBLE
            default: return 4;
        }
    }

    static boolean isDepth(int ifmt) {
        return ifmt == 0x81A5 || ifmt == 0x81A6 || ifmt == 0x81A7 || ifmt == 0x8CAC || ifmt == 0x88F0 || ifmt == 0x8CAD
            || ifmt == DEPTH_COMPONENT || ifmt == DEPTH_STENCIL;
    }

    static boolean hasStencil(int ifmt) {
        return ifmt == 0x88F0 || ifmt == 0x8CAD || ifmt == DEPTH_STENCIL;
    }
}
