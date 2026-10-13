package com.dima.fooddelivery.notification.grpc;

import com.dima.fooddelivery.grpc.identity.v1.GetUserContactRequest;
import com.dima.fooddelivery.grpc.identity.v1.UserContact;
import com.dima.fooddelivery.grpc.identity.v1.UserDirectoryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Клиент каталога пользователей против gRPC-сервера в памяти процесса.
 *
 * <p>Главное — различие между «пользователя нет» и «спросить не удалось»: первое возвращается
 * как пустой результат (письмо слать некому), второе — исключением (повторить позже).
 */
class GrpcUserDirectoryClientTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void shutdown() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    @Test
    void shouldMapContactFields() throws IOException {
        UserDirectoryClient client = clientFor(new FakeDirectory(null, false));

        Optional<UserContactInfo> contact = client.findContact(7L);

        assertAll(
                () -> assertTrue(contact.isPresent()),
                () -> assertEquals(7L, contact.get().userId()),
                () -> assertEquals("user7@example.test", contact.get().email()),
                () -> assertEquals("Анна", contact.get().fullName()),
                () -> assertTrue(contact.get().enabled())
        );
    }

    @Test
    void shouldReturnEmptyForUnknownUser() throws IOException {
        UserDirectoryClient client = clientFor(new FakeDirectory(Status.NOT_FOUND, false));

        assertTrue(client.findContact(404L).isEmpty());
    }

    /** Лежащий identity-service — не «пользователя нет»: иначе при его сбое пропали бы все письма. */
    @Test
    void shouldThrowWhenDirectoryIsUnavailable() throws IOException {
        UserDirectoryClient client = clientFor(new FakeDirectory(Status.UNAVAILABLE, false));

        assertThrows(UserDirectoryClient.DirectoryUnavailableException.class, () -> client.findContact(7L));
    }

    @Test
    void shouldGiveUpAfterDeadline() throws IOException {
        UserDirectoryClient client = clientFor(new FakeDirectory(null, true), Duration.ofMillis(200));

        long started = System.nanoTime();
        assertThrows(UserDirectoryClient.DirectoryUnavailableException.class, () -> client.findContact(7L));

        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_000, "ожидание обязано оборваться по дедлайну");
    }

    private UserDirectoryClient clientFor(FakeDirectory directory) throws IOException {
        return clientFor(directory, Duration.ofSeconds(2));
    }

    private UserDirectoryClient clientFor(FakeDirectory directory, Duration deadline) throws IOException {
        String name = InProcessServerBuilder.generateName();

        Server server = InProcessServerBuilder.forName(name).directExecutor().addService(directory).build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        resources.add(server::shutdownNow);
        resources.add(channel::shutdownNow);

        return new GrpcUserDirectoryClient(channel, deadline);
    }

    private static class FakeDirectory extends UserDirectoryServiceGrpc.UserDirectoryServiceImplBase {

        private final Status failure;
        private final boolean hang;

        FakeDirectory(Status failure, boolean hang) {
            this.failure = failure;
            this.hang = hang;
        }

        @Override
        public void getUserContact(GetUserContactRequest request, StreamObserver<UserContact> observer) {
            if (hang) {
                return;
            }

            if (failure != null) {
                observer.onError(failure.asRuntimeException());
                return;
            }

            observer.onNext(UserContact.newBuilder()
                    .setUserId(request.getUserId())
                    .setEmail("user" + request.getUserId() + "@example.test")
                    .setFullName("Анна")
                    .setEnabled(true)
                    .build());
            observer.onCompleted();
        }
    }
}
