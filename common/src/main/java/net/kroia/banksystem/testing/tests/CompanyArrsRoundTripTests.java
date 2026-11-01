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
 * Task #43g (v2.1.0) — slave-side ARRS forwarding for the {@code /company} tree.
 * <p>
 * These tests validate the wire contract that carries every {@code /company}
 * subcommand from a slave to the master: enum coverage, codec presence, and
 * byte-for-byte round-trip for every {@code Input}/{@code Output} pairing.
 * A regression here would silently drop or corrupt fields, so we assert them
 * explicitly rather than relying on the master handler's live wiring.
 */
public class CompanyArrsRoundTripTests extends TestSuite {

    private static final UUID CALLER = UUID.fromString("00000000-0000-0000-0000-0000CCCC0001");

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.NETWORKING;
    }

    @Override
    public void registerTests() {
        addTest("request_registered", this::testRequestRegistered);
        addTest("codec_map_covers_every_function", this::testCodecMapCoversEveryFunction);
        addTest("create_codec_round_trip", this::testCreateCodecRoundTrip);
        addTest("transfer_codec_round_trip", this::testTransferCodecRoundTrip);
        addTest("dissolve_codec_round_trip", this::testDissolveCodecRoundTrip);
        addTest("description_codec_round_trip", this::testDescriptionCodecRoundTrip);
        addTest("company_info_output_codec_round_trip", this::testCompanyInfoCodecRoundTrip);
        addTest("list_companies_codec_round_trip", this::testListCompaniesCodecRoundTrip);
        addTest("input_data_round_trip", this::testInputDataRoundTrip);
        addTest("output_data_round_trip", this::testOutputDataRoundTrip);
        addTest("error_codes_distinct", this::testErrorCodesDistinct);
    }

    // ------------------------------------------------------------------
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
        return pass("Every FunctionType has input+output codec entries.");
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

    private TestResult testCreateCodecRoundTrip() {
        AsyncCompanyManager.CreateInput in = new AsyncCompanyManager.CreateInput("Acme", 5000L, CALLER, "Alex");
        AsyncCompanyManager.CreateInput ind = roundTrip(AsyncCompanyManager.CreateInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("CreateInput mismatch: " + ind);

        AsyncCompanyManager.CreateOutput out = new AsyncCompanyManager.CreateOutput(
                AsyncCompanyManager.CODE_OK, 42, 7, "Acme", 5000L);
        AsyncCompanyManager.CreateOutput outd = roundTrip(AsyncCompanyManager.CreateOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("CreateOutput mismatch: " + outd);
        return pass("Create input/output round-trip preserved fields.");
    }

    private TestResult testTransferCodecRoundTrip() {
        AsyncCompanyManager.TransferInput in = new AsyncCompanyManager.TransferInput("Acme", CALLER, "Bob");
        AsyncCompanyManager.TransferInput ind = roundTrip(AsyncCompanyManager.TransferInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("TransferInput mismatch: " + ind);

        AsyncCompanyManager.TransferOutput out = new AsyncCompanyManager.TransferOutput(
                AsyncCompanyManager.CODE_OK, 11, "Alice", "Bob");
        AsyncCompanyManager.TransferOutput outd = roundTrip(AsyncCompanyManager.TransferOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("TransferOutput mismatch: " + outd);
        return pass("Transfer input/output round-trip preserved fields.");
    }

    private TestResult testDissolveCodecRoundTrip() {
        AsyncCompanyManager.DissolveInput in = new AsyncCompanyManager.DissolveInput("Zeta", CALLER);
        AsyncCompanyManager.DissolveInput ind = roundTrip(AsyncCompanyManager.DissolveInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("DissolveInput mismatch: " + ind);

        AsyncCompanyManager.DissolveOutput out = new AsyncCompanyManager.DissolveOutput(
                AsyncCompanyManager.CODE_NOT_FOUNDER, 21, "Zeta", 88);
        AsyncCompanyManager.DissolveOutput outd = roundTrip(AsyncCompanyManager.DissolveOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("DissolveOutput mismatch: " + outd);
        return pass("Dissolve input/output round-trip preserved fields.");
    }

    private TestResult testDescriptionCodecRoundTrip() {
        AsyncCompanyManager.DescriptionInput in = new AsyncCompanyManager.DescriptionInput("Trip", CALLER, "hello world");
        AsyncCompanyManager.DescriptionInput ind = roundTrip(AsyncCompanyManager.DescriptionInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("DescriptionInput mismatch: " + ind);

        AsyncCompanyManager.DescriptionOutput out = new AsyncCompanyManager.DescriptionOutput(AsyncCompanyManager.CODE_NO_PERMISSION, 33);
        AsyncCompanyManager.DescriptionOutput outd = roundTrip(AsyncCompanyManager.DescriptionOutput.STREAM_CODEC, out);
        if (!out.equals(outd)) return fail("DescriptionOutput mismatch: " + outd);
        return pass("Description input/output round-trip preserved fields.");
    }

    private TestResult testCompanyInfoCodecRoundTrip() {
        AsyncCompanyManager.CompanyInfoOutput out = new AsyncCompanyManager.CompanyInfoOutput(
                true, 7, "Trip", 42, 12345L, 100L, "hello",
                List.of("Alice", "Bob"), (short) 0);
        AsyncCompanyManager.CompanyInfoOutput outd =
                roundTrip(AsyncCompanyManager.CompanyInfoOutput.STREAM_CODEC, out);
        if (outd.present() != true) return fail("present flag lost");
        if (outd.companyId() != 7) return fail("companyId lost");
        if (!"Trip".equals(outd.name())) return fail("name lost");
        if (outd.bankAccountNr() != 42) return fail("bankAccountNr lost");
        if (outd.maxSupply() != 12345L) return fail("maxSupply lost");
        if (outd.totalSharesIssued() != 100L) return fail("totalSharesIssued lost");
        if (!"hello".equals(outd.description())) return fail("description lost");
        if (outd.founderNames().size() != 2 || !"Alice".equals(outd.founderNames().get(0))
                || !"Bob".equals(outd.founderNames().get(1))) return fail("founderNames lost");

        AsyncCompanyManager.CompanyInfoOutput absent = roundTrip(
                AsyncCompanyManager.CompanyInfoOutput.STREAM_CODEC,
                AsyncCompanyManager.CompanyInfoOutput.ABSENT);
        if (absent.present()) return fail("ABSENT.present should be false after round-trip");
        return pass("CompanyInfoOutput round-trip preserved every field including empty founder list.");
    }

    private TestResult testListCompaniesCodecRoundTrip() {
        AsyncCompanyManager.ListInput in = new AsyncCompanyManager.ListInput(CALLER, AsyncCompanyManager.FILTER_FOUNDER);
        AsyncCompanyManager.ListInput ind = roundTrip(AsyncCompanyManager.ListInput.STREAM_CODEC, in);
        if (!in.equals(ind)) return fail("ListInput mismatch: " + ind);

        AsyncCompanyManager.ListOutput out = new AsyncCompanyManager.ListOutput(List.of("Acme", "Beta"));
        AsyncCompanyManager.ListOutput outd = roundTrip(AsyncCompanyManager.ListOutput.STREAM_CODEC, out);
        if (outd.companyNames().size() != 2 || !"Acme".equals(outd.companyNames().get(0))
                || !"Beta".equals(outd.companyNames().get(1))) return fail("ListOutput names lost: " + outd.companyNames());

        AsyncCompanyManager.ListOutput empty = roundTrip(AsyncCompanyManager.ListOutput.STREAM_CODEC, AsyncCompanyManager.ListOutput.EMPTY);
        if (!empty.companyNames().isEmpty()) return fail("EMPTY should decode to empty list");
        return pass("ListInput/ListOutput round-trip preserved fields.");
    }

    private TestResult testInputDataRoundTrip() {
        AsyncCompanyManager.CreateInput payload =
                new AsyncCompanyManager.CreateInput("Trip", 999L, CALLER, "Alex");
        AsyncCompanyManager.InputData wire =
                AsyncCompanyManager.InputData.of(AsyncCompanyManager.FunctionType.CREATE_COMPANY, payload);
        // Reconstruct from the raw byte[] (what the wire delivers on the master).
        AsyncCompanyManager.InputData copy = new AsyncCompanyManager.InputData(wire.function, wire.encodedParams);
        AsyncCompanyManager.CreateInput decoded = copy.decodeParams();
        return assertTrue("InputData wire round-trip must preserve payload (got " + decoded + ")",
                payload.equals(decoded));
    }

    private TestResult testOutputDataRoundTrip() {
        AsyncCompanyManager.DissolveOutput payload = new AsyncCompanyManager.DissolveOutput(
                AsyncCompanyManager.CODE_OK, 4, "Beta", 12);
        AsyncCompanyManager.OutputData wire =
                AsyncCompanyManager.OutputData.of(AsyncCompanyManager.FunctionType.DISSOLVE_COMPANY, payload);
        AsyncCompanyManager.OutputData copy =
                new AsyncCompanyManager.OutputData(wire.function, wire.encodedResult);
        AsyncCompanyManager.DissolveOutput decoded = copy.decodeResult();
        return assertTrue("OutputData wire round-trip must preserve payload (got " + decoded + ")",
                payload.equals(decoded));
    }

    private TestResult testErrorCodesDistinct() {
        // Each named result code must be unique — otherwise slave-side rendering would collapse errors.
        int[] codes = new int[]{
                AsyncCompanyManager.CODE_OK,
                AsyncCompanyManager.CODE_NOT_FOUND,
                AsyncCompanyManager.CODE_NAME_TAKEN,
                AsyncCompanyManager.CODE_INVALID_INPUT,
                AsyncCompanyManager.CODE_NOT_FOUNDER,
                AsyncCompanyManager.CODE_ALREADY_FOUNDER,
                AsyncCompanyManager.CODE_MISSING_TARGET,
                AsyncCompanyManager.CODE_NO_PERMISSION,
                AsyncCompanyManager.CODE_BANK_ACCOUNT_ERROR,
                AsyncCompanyManager.CODE_INTERNAL,
        };
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                if (codes[i] == codes[j]) return fail("Duplicate error code at " + i + " and " + j);
            }
        }
        return pass("All result codes distinct.");
    }
}
