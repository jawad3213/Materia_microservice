package com.materia.backend.contexts.purchaseOrder.infrastructure.adapters.out.directory;

import com.materia.backend.contexts.purchaseOrder.domain.ports.out.ReceiverDirectory;
import com.materia.backend.support.fixtures.InProcessGrpc;
import com.materia.backend.support.fixtures.TestAccount;
import com.materia.backend.support.fixtures.TestUserDirectory;
import com.materia.proto.identity.v1.UserDirectoryGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.materia.backend.support.fixtures.UserFixtures.aDisabledReceiver;
import static com.materia.backend.support.fixtures.UserFixtures.aPurchaser;
import static com.materia.backend.support.fixtures.UserFixtures.aReceiver;
import static com.materia.backend.support.fixtures.UserFixtures.anInactiveReceiver;
import static org.junit.jupiter.api.Assertions.*;

/** [T044] Only active, enabled receivers can be listed or assigned (US4-7, US4-9). */
class AuthReceiverDirectoryTest {

    private TestUserDirectory users;
    private AuthReceiverDirectory directory;

    @BeforeEach
    void setUp() {
        users = new TestUserDirectory();
        directory = new AuthReceiverDirectory(UserDirectoryGrpc.newBlockingStub(InProcessGrpc.serving(users)));
    }

    @Test
    @DisplayName("list: only enabled, active receivers are listed, sorted by name")
    void list_onlyActiveEnabledReceivers_sorted() {
        users.save(aReceiver().firstName("Zoe").lastName("Z").build());
        TestAccount amy = users.save(aReceiver().firstName("Amy").lastName("A").build());
        users.save(anInactiveReceiver().build());
        users.save(aDisabledReceiver().build());
        users.save(aPurchaser().build());

        List<ReceiverDirectory.Receiver> receivers = directory.findAssignableReceivers();

        assertEquals(List.of("Amy A", "Zoe Z"), receivers.stream().map(ReceiverDirectory.Receiver::name).toList());
        assertEquals(amy.getId().toString(), receivers.get(0).id());
        assertEquals(amy.getEmail(), receivers.get(0).email());
    }

    @Test
    @DisplayName("lookup: an active receiver is found by id")
    void lookup_activeReceiver_isFound() {
        TestAccount rita = users.save(aReceiver().build());

        assertEquals(rita.getFullName(), directory.findAssignableReceiver(rita.getId().toString()).orElseThrow().name());
    }

    @Test
    @DisplayName("lookup: purchasers, inactive and disabled receivers cannot be assigned (US4-7)")
    void lookup_nonAssignableUsers_areRejected() {
        for (TestAccount user : List.of(aPurchaser().build(), anInactiveReceiver().build(), aDisabledReceiver().build())) {
            users.save(user);
            assertTrue(directory.findAssignableReceiver(user.getId().toString()).isEmpty(), user.getEmail());
        }
    }

    @Test
    @DisplayName("lookup: blank, malformed and unknown ids find nobody")
    void lookup_badIds_findNobody() {
        assertTrue(directory.findAssignableReceiver(null).isEmpty());
        assertTrue(directory.findAssignableReceiver("  ").isEmpty());
        assertTrue(directory.findAssignableReceiver("not-a-uuid").isEmpty());
        assertTrue(directory.findAssignableReceiver("00000000-0000-0000-0000-000000000000").isEmpty());
    }

    @Test
    @DisplayName("name: a receiver without a full name is shown by email")
    void name_fallsBackToEmail() {
        TestAccount nameless = users.save(aReceiver().firstName("").lastName("").build());

        String shown = directory.findAssignableReceivers().get(0).name();

        assertEquals(nameless.getEmail(), shown);
    }
}
