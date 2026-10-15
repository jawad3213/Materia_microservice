package com.materia.backend.domain;

import com.materia.backend.common.domain.ports.out.CodeSequenceRepository;
import com.materia.backend.contexts.invoice.application.services.InvoiceCodeGeneratorService;
import com.materia.backend.contexts.invoice.domain.valueObjects.InvoiceCode;
import com.materia.backend.contexts.payment.application.services.PaymentCodeGeneratorService;
import com.materia.backend.contexts.payment.domain.valueObjects.PaymentCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Finance's year-based codes (invoices, payments); the monolith's documents have their own test. */
class FinanceCodeGenerationTest {

    private static class InMemoryCodeSequenceRepository implements CodeSequenceRepository {
        private final Map<String, Integer> sequences = new HashMap<>();

        @Override
        public int getNextValueAndIncrement(String prefix) {
            int current = sequences.getOrDefault(prefix, 1);
            sequences.put(prefix, current + 1);
            return current;
        }
    }

    @Test
    @DisplayName("InvoiceCode and PaymentCode validate year formats")
    void testCodes() {
        int currentYear = Year.now().getValue();

        InvoiceCode inv = InvoiceCode.of("INV-" + currentYear + "-0001");
        assertEquals("INV", inv.getPrefix());
        assertEquals(currentYear, inv.getYear());

        PaymentCode pay = PaymentCode.of("PAY-" + currentYear + "-0001");
        assertEquals("PAY", pay.getPrefix());
        assertEquals(currentYear, pay.getYear());
    }

    @Test
    @DisplayName("Code generators generate codes with current year and sequence")
    void testCodeGeneratorsWithSequence() {
        InMemoryCodeSequenceRepository repo = new InMemoryCodeSequenceRepository();
        int currentYear = Year.now().getValue();

        InvoiceCodeGeneratorService invService = new InvoiceCodeGeneratorService(repo);
        InvoiceCode inv1 = invService.generateCode();
        assertEquals(String.format("INV-%d-0001", currentYear), inv1.getValue());

        PaymentCodeGeneratorService payService = new PaymentCodeGeneratorService(repo);
        PaymentCode pay1 = payService.generateCode();
        assertEquals(String.format("PAY-%d-0001", currentYear), pay1.getValue());
    }
}
