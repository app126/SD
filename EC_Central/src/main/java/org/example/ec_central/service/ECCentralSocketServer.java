package org.example.ec_central.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Service class for handling the central socket server operations.
 */
@Service
@Slf4j
public class ECCentralSocketServer {

    private static final int PORT = 9090;
    private final ClientHandler clientHandler;
    private int lastTaxi = 0;

    /**
     * Constructs a new ECCentralSocketServer with the specified client handler.
     *
     * @param clientHandler the handler for managing client (taxi) connections
     */
    public ECCentralSocketServer(ClientHandler clientHandler) {
        this.clientHandler = clientHandler;
        startServer();
    }

    /**
     * Starts the central server to listen for incoming taxi connections.
     */
    public void startServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                log.info("Central Server started on port " + PORT);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    log.info("New taxi connected from: " + clientSocket.getInetAddress());

                    new Thread(() -> clientHandler.handleTaxiConnection(clientSocket)).start();
                    lastTaxi++;
                }
            } catch (IOException e) {
                log.error("Error starting server: {}", e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }
}