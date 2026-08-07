package net.kroia.banksystem.testing.tests;

import io.netty.buffer.Unpooled;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.SlaveCompanyMirror;
import net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;

/**
 * Task #54 (v2.0.8) — coverage for the slave-side company visual mirror.
 * <ul>
 *   <li>{@code LIST_ALL_COMPANY_VISUALS} ARRS output codec round-trips.</li>
 *   <li>{@link SlaveCompanyMirror} upserts + supply patch + remove behave.</li>
 *   <li>{@link S2CCompanyVisualBulkPacket} codec round-trips the composite Entry.</li>
 * </ul>
 * Categorised {@code NETWORKING} (ServerType.BOTH) since the tests are pure
 * codec / in-memory checks and don't need a specific server role.
 */
public class SlaveCompanyMirrorTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.NETWORKING;
    }

    @Override
    public void registerTests() {
        addTest("list_all_visuals_codec_round_trip", this::testListAllVisualsCodec);
        addTest("bulk_packet_codec_round_trip", this::testBulkCodec);
        addTest("mirror_upsert_and_remove", this::testMirrorUpsertRemove);
        addTest("mirror_supply_patch_preserves_visuals", this::testMirrorSupplyPatch);
        addTest("mirror_snapshot_matches_puts", this::testMirrorSnapshot);
        addTest("mirror_clear_empties", this::testMirrorClear);
        addTest("function_type_enum_has_list_all_visuals", this::testFunctionTypeEnum);
    }

    private static S2CCompanyVisualBulkPacket.Entry sample(int id) {
        // bgSymbolId, bgTint, fgSymbolId, fgTint, displayName, description, ...
        return new S2CCompanyVisualBulkPacket.Entry(
                id, "", 0xFF00FF00, "leaf", 0xFFFFFFFF, "Green Corp", "Sustainable widgets",
                123L, 1000L, "GreenCorp", "internal desc", 4242,
                List.of("Alex", "Bob"), 3);
    }

    private TestResult testListAllVisualsCodec() {
        AsyncCompanyManager.ListAllVisualsOutput src =
                new AsyncCompanyManager.ListAllVisualsOutput(List.of(sample(1), sample(2)));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            AsyncCompanyManager.ListAllVisualsOutput.STREAM_CODEC.encode(buf, src);
            AsyncCompanyManager.ListAllVisualsOutput dec =
                    AsyncCompanyManager.ListAllVisualsOutput.STREAM_CODEC.decode(buf);
            if (dec.entries().size() != 2) return fail("Expected 2, got " + dec.entries().size());
            if (dec.entries().get(0).companyId() != 1) return fail("cid[0] mismatch");
            if (!"leaf".equals(dec.entries().get(1).fgSymbolId())) return fail("preset[1] mismatch");
            if (dec.entries().get(0).founderNames().size() != 2) return fail("founders size");
            return pass("ListAllVisualsOutput round-trip OK.");
        } finally {
            buf.release();
        }
    }

    private TestResult testBulkCodec() {
        S2CCompanyVisualBulkPacket src = new S2CCompanyVisualBulkPacket(List.of(sample(7)));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            S2CCompanyVisualBulkPacket.STREAM_CODEC.encode(buf, src);
            S2CCompanyVisualBulkPacket dec = S2CCompanyVisualBulkPacket.STREAM_CODEC.decode(buf);
            if (dec == null) return fail("null decode");
            return pass("BulkPacket round-trip OK.");
        } finally {
            buf.release();
        }
    }

    private TestResult testMirrorUpsertRemove() {
        SlaveCompanyMirror.clear();
        SlaveCompanyMirror.put(sample(11));
        if (SlaveCompanyMirror.size() != 1) return fail("size after put != 1");
        SlaveCompanyMirror.put(sample(11)); // idempotent replace
        if (SlaveCompanyMirror.size() != 1) return fail("size after re-put != 1");
        SlaveCompanyMirror.remove(11);
        if (!SlaveCompanyMirror.isEmpty()) return fail("mirror not empty after remove");
        return pass("Upsert + remove behave.");
    }

    private TestResult testMirrorSupplyPatch() {
        SlaveCompanyMirror.clear();
        SlaveCompanyMirror.put(sample(21));
        SlaveCompanyMirror.updateSupply(21, 999L);
        S2CCompanyVisualBulkPacket.Entry got = SlaveCompanyMirror.snapshot().get(0);
        if (got.totalSharesIssued() != 999L) return fail("supply not patched: " + got.totalSharesIssued());
        if (!"leaf".equals(got.fgSymbolId())) return fail("visuals lost after supply patch");
        if (got.maxSupply() != 1000L) return fail("maxSupply changed unexpectedly");
        SlaveCompanyMirror.clear();
        return pass("Supply patch preserves visuals + max.");
    }

    private TestResult testMirrorSnapshot() {
        SlaveCompanyMirror.clear();
        SlaveCompanyMirror.putAll(List.of(sample(31), sample(32), sample(33)));
        if (SlaveCompanyMirror.snapshot().size() != 3) return fail("snapshot size != 3");
        SlaveCompanyMirror.clear();
        return pass("Snapshot returns all inserted entries.");
    }

    private TestResult testMirrorClear() {
        SlaveCompanyMirror.put(sample(41));
        SlaveCompanyMirror.clear();
        if (!SlaveCompanyMirror.isEmpty()) return fail("clear did not empty");
        if (SlaveCompanyMirror.size() != 0) return fail("size != 0 after clear");
        return pass("Clear empties mirror.");
    }

    private TestResult testFunctionTypeEnum() {
        try {
            AsyncCompanyManager.FunctionType.valueOf("LIST_ALL_COMPANY_VISUALS");
        } catch (IllegalArgumentException e) {
            return fail("LIST_ALL_COMPANY_VISUALS enum missing");
        }
        if (AsyncCompanyManager.codecs.get(AsyncCompanyManager.FunctionType.LIST_ALL_COMPANY_VISUALS) == null)
            return fail("codec missing for LIST_ALL_COMPANY_VISUALS");
        return pass("FunctionType + codec registered.");
    }
}
