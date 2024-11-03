package org.example.ec_customer.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

/**
 * Service class for handling Kafka messages related to client responses.
 */
@Service
@Slf4j
public class ClientKafkaListener {

    /**
     * Semaphore to control the flow of message processing.
     */
    public static Semaphore semaphore = new Semaphore(1);

    /**
     * Listens for messages from the Kafka topic associated with the client ID.
     *
     * @param message the message received from the Kafka topic
     */
    @KafkaListener(topics = "#{@clientIdTopic}", groupId = "group")
    public void listenToClientResponses(String message) {
        log.info("Received message from server: {}", message);

        processMessage(message);
    }

    /**
     * Processes the received message and logs the appropriate response.
     *
     * @param message the message to be processed
     */
    private void processMessage(String message) {
        if (message.startsWith("OK")) {
            log.info("Taxi assigned successfully: {}", message);
        } else {
            semaphore.release();
            log.error("Failed to assign taxi: {}", message);
        }
        if (message.equals("END")) {
            semaphore.release();
        }
    }
}