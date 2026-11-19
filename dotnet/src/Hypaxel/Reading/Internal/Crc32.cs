using System.Buffers.Binary;
using System.Runtime.InteropServices;
using ArmCrc32 = System.Runtime.Intrinsics.Arm.Crc32;

namespace Hypaxel.Internal.Reading;

/// <summary>
/// CRC-32 (IEEE, as used by ZIP). System.IO.Hashing is a separate package, and ZipArchive only verifies
/// checksums from .NET 11 on, so the reader computes them itself.
/// </summary>
internal static class Crc32
{
    private static readonly uint[] s_table = BuildTable();

    /// <summary>Feeds <paramref name="data"/> into a running CRC. Start with <c>0xFFFFFFFF</c> and invert the result.</summary>
    public static uint Update(uint crc, ReadOnlySpan<byte> data)
    {
        if (ArmCrc32.Arm64.IsSupported)
        {
            while (data.Length >= 8)
            {
                crc = ArmCrc32.Arm64.ComputeCrc32(crc, MemoryMarshal.Read<ulong>(data));
                data = data[8..];
            }

            foreach (var b in data)
            {
                crc = ArmCrc32.ComputeCrc32(crc, b);
            }

            return crc;
        }

        return UpdateWithTable(crc, data);
    }

    /// <summary>The portable slicing-by-8 implementation (used where the CPU has no CRC-32 instructions).</summary>
    internal static uint UpdateWithTable(uint crc, ReadOnlySpan<byte> data)
    {
        var table = s_table;
        while (data.Length >= 8)
        {
            var one = BinaryPrimitives.ReadUInt32LittleEndian(data) ^ crc;
            var two = BinaryPrimitives.ReadUInt32LittleEndian(data[4..]);
            crc = table[(7 * 256) + (one & 0xFF)]
                ^ table[(6 * 256) + ((one >> 8) & 0xFF)]
                ^ table[(5 * 256) + ((one >> 16) & 0xFF)]
                ^ table[(4 * 256) + (one >> 24)]
                ^ table[(3 * 256) + (two & 0xFF)]
                ^ table[(2 * 256) + ((two >> 8) & 0xFF)]
                ^ table[256 + ((two >> 16) & 0xFF)]
                ^ table[two >> 24];
            data = data[8..];
        }

        foreach (var b in data)
        {
            crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8);
        }

        return crc;
    }

    public static uint Compute(ReadOnlySpan<byte> data) => ~Update(0xFFFFFFFF, data);

    private static uint[] BuildTable()
    {
        var table = new uint[8 * 256];
        for (uint i = 0; i < 256; i++)
        {
            var c = i;
            for (var k = 0; k < 8; k++)
            {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
            }

            table[i] = c;
        }

        for (var i = 0; i < 256; i++)
        {
            for (var slice = 1; slice < 8; slice++)
            {
                var previous = table[((slice - 1) * 256) + i];
                table[(slice * 256) + i] = (previous >> 8) ^ table[previous & 0xFF];
            }
        }

        return table;
    }
}
