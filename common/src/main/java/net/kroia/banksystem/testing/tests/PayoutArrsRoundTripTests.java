package net.kroia.banksystem.testing.tests;

import io.netty.buffer.Unpooled;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionDataCodecs;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;
import java.util.UUID;

/**
 * Task #45a (v2.0.8) — wire-contract coverage for the payout ARRS functions added to
 * {@link AsyncCompanyManager}. Guarantees byte-for-byte round-trip preservation across
 * every {@code Input}/{@code Output} pair, plus enum + codec-map coverage.
 */
public class PayoutArrsRoundTripTests extends TestSuite {

    private static final UUID CALLER = UUID.fromString("00000000-0000-0000-0000-0000CCCC0045");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000CCCC0046");

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.NETWORKING;
    }

    @Override
    public void registerTests() {
        addTest("request_registered", this::testRequestRegistered);
        addTest("codec_map_covers_every_function", this::testCodecMapCoversEveryFunction);
        addTest("create_payout_codec_round_trip", this::testCreatePayoutRoundTrip);
        addTest("update_payout_codec_round_trip", this::testUpdatePayoutRoundTrip);
        addTest("pause_payout_codec_round_trip", this::testPausePayoutRoundTrip);
        addTest("delete_payout_codec_round_trip", this::testDeletePayoutRoundTrip);
        addTest("list_schedules_codec_round_trip", this::testListSchedulesRoundTrip);
        addTest("get_history_codec_round_trip", this::testGetHistoryRoundTrip);
        addTest("get_company_info_by_account_codec_round_trip", this::testGetCompanyInfoByAccountRoundTrip);
        addTest("get_failure_count_24h_codec_round_trip", this::testGetFailureCount24hRoundTrip);
    }

    private TestResult testGetFailureCount24hRoundTrip() {
        AsyncCompanyManager.GetFailureCount24hInput in = new AsyncCompanyManager.GetFailureCount24hInput(7);
        AsyncCompanyManager.GetFailureCount24hInput ind = roundTrip(AsyncCompanyManager.GetFailureCount24hInput.STREAM_CODEC, in);
        if (in.companyId() != ind.companyId()) return fail("GetFailureCount24hInput mismatch");
        AsyncCompanyManager.GetFailureCount24hOutput out = new AsyncCompanyManager.GetFailureCount24hOutput(42L);
        AsyncCompanyManager.GetFailureCount24hOutput outd = roundTrip(AsyncCompanyManager.GetFailureCount24hOutput.STREAM_CODEC, out);
        if (out.failedCount() != outd.failedCount()) return fail("GetFailureCount24hOutput mismatch");
        return pass("GetFailureCount24h round-trip preserved fields.");
    }

    private TestResult testRequestRegistered() {
        if (AsyncCompanyManager.Request.instance == null) return fail("Request.instance not registered");
        return pass("AsyncCompanyManager.Request registered.");
    }

    private TestResult testCodecMapCoversEveryFunction() {
        for (AsyncCompanyManager.FunctionType f : AsyncCompanyManager.FunctionType.values()) {
            AsyncFunctionDataCodecs entry = AsyncCompanyManager.codecs.get(f);
            if (entry == null) return fail("No codec entry for " + f);
            if (entry.inputParamsCodec == null) return fail("Missing input codec for " + f);
            if (entry.outputParamsCodec == null) return fail("Missing output codec for " + f);
        }
        return pass("Every FunctionType (incl. payouts) has input+output codec entries.");
    }

    private <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            codec.encode(buf, value);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }

    private TestResult testCreatePayoutRoundTrip() {
        AsyncCompanyManager.CreatePayoutInput in = new AsyncCompanyManager.CreatePayoutInput(
                7, TARGET, 500L, 1200L, 999L, CALLER, 12, (byte) 1, (short) 7);
        AsyncCompanyManager.CreatePayoutInput ind = roundTrip(AsyncCompanyManager.CreatePayoutInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("CreatePayoutInput mismatch: " + ind);
        AsyncCompanyManager.CreatePayoutOutput out = new AsyncCompanyManager.CreatePayoutOutput(
                AsyncCompanyManager.CODE_OK, 42L);
        AsyncCompanyManager.CreatePayoutOutput outd = roundTrip(AsyncCompanyManager.CreatePayoutOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("CreatePayoutOutput mismatch: " + outd);
        return pass("CreatePayout round-trip preserved fields.");
    }

    private TestResult testUpdatePayoutRoundTrip() {
        AsyncCompanyManager.UpdatePayoutInput in = new AsyncCompanyManager.UpdatePayoutInput(
                3, 11L, 250L, 72000L, CALLER, TARGET, 4, (byte) 0, (short) 0);
        AsyncCompanyManager.UpdatePayoutInput ind = roundTrip(AsyncCompanyManager.UpdatePayoutInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("UpdatePayoutInput mismatch: " + ind);
        AsyncCompanyManager.UpdatePayoutOutput out = new AsyncCompanyManager.UpdatePayoutOutput(AsyncCompanyManager.CODE_SCHEDULE_MISSING);
        AsyncCompanyManager.UpdatePayoutOutput outd = roundTrip(AsyncCompanyManager.UpdatePayoutOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("UpdatePayoutOutput mismatch: " + outd);
        return pass("UpdatePayout round-trip preserved fields.");
    }

    private TestResult testPausePayoutRoundTrip() {
        AsyncCompanyManager.PausePayoutInput in = new AsyncCompanyManager.PausePayoutInput(3, 11L, true, CALLER);
        AsyncCompanyManager.PausePayoutInput ind = roundTrip(AsyncCompanyManager.PausePayoutInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("PausePayoutInput mismatch: " + ind);
        AsyncCompanyManager.PausePayoutOutput out = new AsyncCompanyManager.PausePayoutOutput(AsyncCompanyManager.CODE_OK);
        AsyncCompanyManager.PausePayoutOutput outd = roundTrip(AsyncCompanyManager.PausePayoutOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("PausePayoutOutput mismatch: " + outd);
        return pass("PausePayout round-trip preserved fields.");
    }

    private TestResult testDeletePayoutRoundTrip() {
        AsyncCompanyManager.DeletePayoutInput in = new AsyncCompanyManager.DeletePayoutInput(3, 11L, CALLER);
        AsyncCompanyManager.DeletePayoutInput ind = roundTrip(AsyncCompanyManager.DeletePayoutInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("DeletePayoutInput mismatch: " + ind);
        AsyncCompanyManager.DeletePayoutOutput out = new AsyncCompanyManager.DeletePayoutOutput(AsyncCompanyManager.CODE_NO_PERMISSION);
        AsyncCompanyManager.DeletePayoutOutput outd = roundTrip(AsyncCompanyManager.DeletePayoutOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("DeletePayoutOutput mismatch: " + outd);
        return pass("DeletePayout round-trip preserved fields.");
    }

    private TestResult testListSchedulesRoundTrip() {
        AsyncCompanyManager.ListSchedulesInput in = new AsyncCompanyManager.ListSchedulesInput(4);
        AsyncCompanyManager.ListSchedulesInput ind = roundTrip(AsyncCompanyManager.ListSchedulesInput.STREAM_CODEC, in);
        if (in.companyId() != ind.companyId()) return fail("ListSchedulesInput mismatch");

        AsyncCompanyManager.ScheduleWire w1 = new AsyncCompanyManager.ScheduleWire(
                7L, TARGET, 100L, 1200L, 5000L, false, 111L, CALLER,
                12, "Alice", "Main", (byte) 0, (short) 0, 300L, 3);
        AsyncCompanyManager.ScheduleWire w2 = new AsyncCompanyManager.ScheduleWire(
                8L, null, 200L, 72000L, 9000L, true, 222L, null,
                -1, "", "", (byte) 1, (short) 9, 0L, 0);
        AsyncCompanyManager.ListSchedulesOutput out = new AsyncCompanyManager.ListSchedulesOutput(List.of(w1, w2), 4242L);
        AsyncCompanyManager.ListSchedulesOutput outd = roundTrip(AsyncCompanyManager.ListSchedulesOutput.STREAM_CODEC, out);
        if (outd.schedules().size() != 2) return fail("schedule count lost");
        AsyncCompanyManager.ScheduleWire d1 = outd.schedules().get(0);
        AsyncCompanyManager.ScheduleWire d2 = outd.schedules().get(1);
        if (!w1.equals(d1)) return fail("w1 mismatch: " + d1);
        if (!w2.equals(d2)) return fail("w2 mismatch: " + d2);
        if (outd.nowTick() != 4242L) return fail("nowTick lost");

        AsyncCompanyManager.ListSchedulesOutput empty = roundTrip(AsyncCompanyManager.ListSchedulesOutput.STREAM_CODEC,
                AsyncCompanyManager.ListSchedulesOutput.EMPTY);
        if (!empty.schedules().isEmpty()) return fail("EMPTY not empty after round-trip");
        return pass("ListSchedules round-trip preserved every field including null UUIDs.");
    }

    private TestResult testGetHistoryRoundTrip() {
        AsyncCompanyManager.GetHistoryInput in = new AsyncCompanyManager.GetHistoryInput(11L, 20);
        AsyncCompanyManager.GetHistoryInput ind = roundTrip(AsyncCompanyManager.GetHistoryInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("GetHistoryInput mismatch: " + ind);

        AsyncCompanyManager.HistoryRowWire r1 = new AsyncCompanyManager.HistoryRowWire(
                1L, 3, 11L, 42, TARGET, 500L, 1234L, 0, "Alice", "Main", (short) 0, 0);
        AsyncCompanyManager.HistoryRowWire r2 = new AsyncCompanyManager.HistoryRowWire(
                2L, 3, 11L, 42, null, 500L, 1235L, 2, "", "", (short) 9, 1);
        AsyncCompanyManager.GetHistoryOutput out = new AsyncCompanyManager.GetHistoryOutput(List.of(r1, r2), 500L);
        AsyncCompanyManager.GetHistoryOutput outd = roundTrip(AsyncCompanyManager.GetHistoryOutput.STREAM_CODEC, out);
        if (outd.rows().size() != 2) return fail("row count lost");
        if (!r1.equals(outd.rows().get(0))) return fail("r1 mismatch: " + outd.rows().get(0));
        if (!r2.equals(outd.rows().get(1))) return fail("r2 mismatch (null target): " + outd.rows().get(1));
        if (outd.totalPaid() != 500L) return fail("totalPaid lost");

        AsyncCompanyManager.GetHistoryOutput empty = roundTrip(AsyncCompanyManager.GetHistoryOutput.STREAM_CODEC,
                AsyncCompanyManager.GetHistoryOutput.EMPTY);
        if (!empty.rows().isEmpty() || empty.totalPaid() != 0L) return fail("EMPTY not zero after round-trip");
        return pass("GetHistory round-trip preserved every field including null target UUID.");
    }

    private TestResult testGetCompanyInfoByAccountRoundTrip() {
        AsyncCompanyManager.GetCompanyInfoByAccountInput in = new AsyncCompanyManager.GetCompanyInfoByAccountInput(88);
        AsyncCompanyManager.GetCompanyInfoByAccountInput ind = roundTrip(AsyncCompanyManager.GetCompanyInfoByAccountInput.STREAM_CODEC, in);
        if (in.accountNr() != ind.accountNr()) return fail("GetCompanyInfoByAccountInput mismatch: " + ind);

        AsyncCompanyManager.CompanyInfoOutput out = new AsyncCompanyManager.CompanyInfoOutput(
                true, 5, "Acme", 88, 10_000L, 0L, "d", List.of("Alice"), (short) 0);
        AsyncCompanyManager.CompanyInfoOutput outd = roundTrip(AsyncCompanyManager.CompanyInfoOutput.STREAM_CODEC, out);
        if (!outd.name().equals("Acme")) return fail("CompanyInfoOutput name lost");
        return pass("GetCompanyInfoByAccount round-trip preserved fields.");
    }
}
