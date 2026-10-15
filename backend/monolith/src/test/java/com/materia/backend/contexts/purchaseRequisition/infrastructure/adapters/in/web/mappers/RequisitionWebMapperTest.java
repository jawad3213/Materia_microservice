package com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.in.web.mappers;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.valueObjects.Money;
import com.materia.backend.contexts.purchaseRequisition.application.dtos.RequisitionLineOutput;
import com.materia.backend.contexts.purchaseRequisition.application.dtos.RequisitionLineSummary;
import com.materia.backend.contexts.purchaseRequisition.application.dtos.RequisitionOutput;
import com.materia.backend.contexts.purchaseRequisition.domain.entities.RequisitionLine;
import com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.in.web.dtos.response.RequisitionLineWebResponse;
import com.materia.backend.contexts.purchaseRequisition.infrastructure.adapters.in.web.dtos.response.RequisitionWebResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A requisition's lines reach the web response as copies (RequisitionLineOutput), every field of them. */
class RequisitionWebMapperTest {

    private final RequisitionWebMapper mapper = new RequisitionWebMapper();

    @Test
    @DisplayName("toWebResponse: every field of a line is shown, amounts formatted")
    void line_everyField() {
        RequisitionLine line = fullLine();
        RequisitionOutput output = new RequisitionOutput();
        output.setLines(List.of(RequisitionLineOutput.from(line)));

        RequisitionWebResponse response = mapper.toWebResponse(output);

        assertThat(response.getLines()).hasSize(1);
        RequisitionLineWebResponse web = response.getLines().get(0);
        assertThat(web.getId()).isEqualTo(line.getId());
        assertThat(web.getLineNumber()).isEqualTo(2);
        assertThat(web.getMaterialCode()).isEqualTo("MAT-7");
        assertThat(web.getMaterialId()).isEqualTo(line.getMaterialId());
        assertThat(web.getMaterialName()).isEqualTo("Steel bolt");
        assertThat(web.getMaterialDescription()).isEqualTo("M8, zinc plated");
        assertThat(web.getUnitOfMeasure()).isEqualTo("PCS");
        assertThat(web.getStandardPrice()).isEqualTo(line.getStandardPrice().format());
        assertThat(web.getUnitPrice()).isEqualTo(line.getUnitPrice().format());
        assertThat(web.getCurrencyCode()).isEqualTo("MAD");
        assertThat(web.getQuantity()).isEqualTo(10);
        assertThat(web.getQuantityReceived()).isEqualTo(4);
        assertThat(web.getQuantityRejected()).isEqualTo(1);
        assertThat(web.getRequiredDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(web.getLineTotal()).isEqualTo(line.getLineTotal().format());
        assertThat(web.getCurrencyCodeLine()).isEqualTo("MAD");
        assertThat(web.getSupplierId()).isEqualTo(line.getSupplierId());
        assertThat(web.getSupplierName()).isEqualTo("Acme Supplies");
        assertThat(web.getSupplierCode()).isEqualTo("SUP-3");
        assertThat(web.getNotes()).isEqualTo("Urgent");
        assertThat(web.getDeliveryTerms()).isEqualTo("DAP");
        assertThat(web.getStorageLocation()).isEqualTo("A-12");
        assertThat(web.getBatchNumber()).isEqualTo("B-2026-11");
        assertThat(web.getExpiryDate()).isEqualTo(LocalDate.of(2028, 1, 31));
    }

    @Test
    @DisplayName("a line without prices shows none, and the output keeps no link to the domain line")
    void line_withoutPrices_andDetached() {
        RequisitionLine line = new RequisitionLine("MAT-1", 3);
        line.setId(UUID.randomUUID());
        RequisitionOutput output = new RequisitionOutput();
        output.setLines(List.of(RequisitionLineOutput.from(line)));

        line.setQuantity(99);
        RequisitionLineWebResponse web = mapper.toWebResponse(output).getLines().get(0);

        assertThat(web.getUnitPrice()).isNull();
        assertThat(web.getStandardPrice()).isNull();
        assertThat(web.getLineTotal()).isNull();
        assertThat(web.getQuantity()).isEqualTo(3);
        assertThat(output.getLineSummaries())
                .containsExactly(new RequisitionLineSummary(line.getId(), "MAT-1", 3, null));
    }

    private static RequisitionLine fullLine() {
        RequisitionLine line = new RequisitionLine("MAT-7", 10);
        line.setId(UUID.randomUUID());
        line.setLineNumber(2);
        line.setMaterialId(UUID.randomUUID());
        line.setMaterialName("Steel bolt");
        line.setMaterialDescription("M8, zinc plated");
        line.setUnitOfMeasure("PCS");
        line.setStandardPrice(Money.of("4.50", CurrencyCode.MAD));
        line.setUnitPrice(Money.of("4.00", CurrencyCode.MAD));
        line.setCurrencyCode("MAD");
        line.setQuantityReceived(4);
        line.setQuantityRejected(1);
        line.setRequiredDate(LocalDate.of(2026, 11, 2));
        line.setLineTotal(Money.of("40.00", CurrencyCode.MAD));
        line.setCurrencyCodeLine("MAD");
        line.setSupplierId(UUID.randomUUID());
        line.setSupplierName("Acme Supplies");
        line.setSupplierCode("SUP-3");
        line.setNotes("Urgent");
        line.setDeliveryTerms("DAP");
        line.setStorageLocation("A-12");
        line.setBatchNumber("B-2026-11");
        line.setExpiryDate(LocalDate.of(2028, 1, 31));
        return line;
    }
}
