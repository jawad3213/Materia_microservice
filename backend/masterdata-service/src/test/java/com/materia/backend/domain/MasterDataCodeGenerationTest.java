package com.materia.backend.domain;

import com.materia.backend.common.domain.ports.out.CodeSequenceRepository;
import com.materia.backend.contexts.masterData.application.services.CategoryCodeGeneratorService;
import com.materia.backend.contexts.masterData.application.services.MaterialCodeGeneratorService;
import com.materia.backend.contexts.masterData.application.services.SupplierCodeGeneratorService;
import com.materia.backend.contexts.masterData.domain.enums.MaterialType;
import com.materia.backend.contexts.masterData.domain.valueObjects.MaterialCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Master data's year-based codes (materials, suppliers, categories); procurement's documents have their own test. */
class MasterDataCodeGenerationTest {

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
    @DisplayName("MaterialCode supports year-based formats and backward compatibility")
    void testMaterialCodeValidationAndParsing() {
        int currentYear = Year.now().getValue();

        MaterialCode withYear = MaterialCode.of("MAT-" + currentYear + "-0001");
        assertEquals("MAT", withYear.getPrefix());
        assertEquals("0001", withYear.getNumber());
        assertEquals(1, withYear.getNumberAsInt());
        assertEquals(currentYear, withYear.getYear());

        MaterialCode lowerCase = MaterialCode.of("material-" + currentYear + "-0005");
        assertEquals("material", lowerCase.getPrefix());
        assertEquals(5, lowerCase.getNumberAsInt());

        MaterialCode legacy = MaterialCode.of("MAT-0042");
        assertEquals("MAT", legacy.getPrefix());
        assertEquals(42, legacy.getNumberAsInt());
        assertEquals(-1, legacy.getYear());

        MaterialCode generated = MaterialCode.fromPrefixAndNumber("MAT", 7);
        assertEquals(String.format("MAT-%d-0007", currentYear), generated.getValue());

        MaterialCode next = MaterialCode.generateNext(withYear);
        assertEquals(String.format("MAT-%d-0002", currentYear), next.getValue());
    }

    @Test
    @DisplayName("Code generators generate codes with current year and sequence")
    void testCodeGeneratorsWithSequence() {
        InMemoryCodeSequenceRepository repo = new InMemoryCodeSequenceRepository();
        int currentYear = Year.now().getValue();

        MaterialCodeGeneratorService matService = new MaterialCodeGeneratorService(repo);
        MaterialCode mat1 = matService.generateCode(MaterialType.RAW_MATERIAL);
        assertEquals(String.format("RMT-%d-0001", currentYear), mat1.getValue());
        MaterialCode mat2 = matService.generateCode(MaterialType.RAW_MATERIAL);
        assertEquals(String.format("RMT-%d-0002", currentYear), mat2.getValue());

        SupplierCodeGeneratorService supService = new SupplierCodeGeneratorService(repo);
        String sup1 = supService.generateCode();
        assertEquals(String.format("SUP-%d-0001", currentYear), sup1);

        CategoryCodeGeneratorService catService = new CategoryCodeGeneratorService(repo);
        String cat1 = catService.generateCode();
        assertEquals(String.format("CAT-%d-0001", currentYear), cat1);
    }
}
