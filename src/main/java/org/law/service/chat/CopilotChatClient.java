package org.law.service.chat;

import com.github.copilot.sdk.CopilotClient;
import com.github.copilot.sdk.generated.AssistantMessageEvent;
import com.github.copilot.sdk.json.MessageOptions;
import com.github.copilot.sdk.json.PermissionHandler;
import com.github.copilot.sdk.json.SessionConfig;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client pour communiquer avec GitHub Copilot SDK Java.
 */
public class CopilotChatClient implements AutoCloseable {

    private final CopilotClient client;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public CopilotChatClient() {
        this.client = new CopilotClient();
    }

    private void ensureStarted() throws IOException {
        if (started.get()) {
            return;
        }

        synchronized (this) {
            if (started.get()) {
                return;
            }
            try {
                client.start().get();
                started.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Demarrage Copilot interrompu", e);
            } catch (ExecutionException e) {
                throw new IOException("Impossible de demarrer GitHub Copilot SDK", e);
            }
        }
    }

    public boolean isAvailable() {
        try {
            ensureStarted();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String generate(String prompt, String model) throws IOException {
        return generate(prompt, model, 0.7);
    }

    public String generate(String prompt, String model, double temperature) throws IOException {
        ensureStarted();

        AtomicReference<String> lastMessage = new AtomicReference<>(null);

        try {
            var session = client.createSession(
                    new SessionConfig()
                            .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                            .setModel(model))
                    .get();

            session.on(AssistantMessageEvent.class, msg -> {
                var content = msg.getData().content();
                if (content != null && !content.isBlank()) {
                    lastMessage.set(content);
                }
            });

            // temperature est conservee dans la signature pour compatibilite
            session.sendAndWait(new MessageOptions().setPrompt(prompt)).get();

            String response = lastMessage.get();
            if (response == null || response.isBlank()) {
                throw new IOException("Aucune reponse generee par GitHub Copilot SDK");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requete Copilot interrompue", e);
        } catch (ExecutionException e) {
            throw new IOException("Erreur Copilot SDK lors de la generation", e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
