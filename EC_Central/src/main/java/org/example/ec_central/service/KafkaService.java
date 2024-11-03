package org.example.ec_central.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.example.ec_central.model.*;
import org.example.ec_central.model.entity.Customer;
import org.example.ec_central.model.entity.CustomerTaxiAssignment;
import org.example.ec_central.model.entity.Location;
import org.example.ec_central.model.entity.Taxi;
import org.example.ec_central.repository.CustomerRepository;
import org.example.ec_central.repository.CustomerTaxiAssignmentRepository;
import org.example.ec_central.repository.LocationRepository;
import org.example.ec_central.repository.TaxiRepository;
import org.example.ec_central.utils.MappingUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service class for handling Kafka-related operations.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaService {
    private final KafkaAdmin kafkaAdmin;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Map<String, String> clientTopics = new ConcurrentHashMap<>();
    private final TaxiService taxiService;
    private final TaxiWebSocketService taxiWebSocketService;
    private final ClientHandler clientHandler;
    private final TaxiRepository taxiRepository;
    private final LocationRepository locationRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final CustomerTaxiAssignmentRepository customerTaxiAssignmentRepository;
    private final CustomerTaxiAssignmentService customerTaxiAssignmentService;

    private CityMap cityMap;

    /**
     * Initializes the city map after the bean is constructed.
     */
    @PostConstruct
    private void fillCityMap() {
        cityMap = new CityMap();
        new Thread(() -> runThread()).start();
    }

    private void runThread() {
        while(true) {
            try {
                taxiWebSocketService.broadcastToClients(populateMap());
                Thread.sleep(1000);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    /**
     * Publishes an event indicating that a taxi has been assigned.
     *
     * @param taxi the taxi that has been assigned
     */
    public void publishTaxiAssignedEvent(Taxi taxi) {
        String message = "Taxi " + taxi.getIdentifier() + " assigned to position " + taxi.getPosition();
        kafkaTemplate.send("taxi-status", message);
        this.logKafkaSend("Published Kafka event: " + message, "taxi-status");
    }

    /**
     * Listens for taxi status updates from Kafka.
     *
     * @param message the message received from Kafka
     */
    @KafkaListener(topics = "taxi-status", groupId = "group")
    public void listenTaxiStatusUpdates(String message) {
        log.info("Received Kafka message: {}", message);

        if (message.contains("has arrived at destination")) {
            String taxiIdentifier = extractTaxiIdentifier(message);
            taxiService.findTaxiByIdentifier(taxiIdentifier)
                    .ifPresent(taxiService::releaseTaxi);
            log.info("Taxi {} has been released.", taxiIdentifier);
        }
    }

    /**
     * Extracts the taxi identifier from a message.
     *
     * @param message the message containing the taxi identifier
     * @return the extracted taxi identifier
     */
    private String extractTaxiIdentifier(String message) {
        return message.split(" ")[1];
    }

    /**
     * Listens for service requests from Kafka.
     *
     * @param message the message received from Kafka
     */
    @KafkaListener(topics = "service_requests", groupId = "group", containerFactory = "stringKafkaListenerContainerFactory")
    public void listenServiceRequest(String message) {
        log.info("Received Kafka message: {}", message);
        String[] parts = message.split("#");
        String clientId = parts[0];
        String destination = parts[1];
        Customer customer = customerService.getCustomerByIdentifier(clientId);

        customer.setDestIdentifier(destination);
        customer.setState(CustomerState.REQUESTING);
        customerRepository.save(customer);

        createClientTopic(clientId);

        boolean taxiAssigned = assignTaxiToClient(customer, destination);

        String response;

        if (taxiAssigned) {
            response = "OK: Taxi asignado";
            customer.setState(CustomerState.WAITING_FOR_TAXI);
            customerRepository.save(customer);

            log.info("OK: Taxi assigned: {}", message);

        } else {
            response = "KO: Taxis no disponible";
            log.error("KO: Taxi not available: {}", message);
        }
        publishToClient(customer, response);
    }

    /**
     * Creates a Kafka topic for a client if it does not already exist.
     *
     * @param clientId the identifier of the client
     */
    public void createClientTopic(String clientId) {
        String topicName = "taxi-requests-" + clientId;
        if (!clientTopics.containsKey(clientId)) {
            NewTopic newTopic = new NewTopic(topicName, 1, (short) 1);
            kafkaAdmin.createOrModifyTopics(newTopic);
            clientTopics.put(clientId, topicName);
            log.info("Tópico creado para el cliente: {}", topicName);
        }
    }

    /**
     * Publishes a message to a client's Kafka topic.
     *
     * @param customer the customer to whom the message is addressed
     * @param message the message to be published
     */
    public void publishToClient(Customer customer, String message) {
        String topicName = "taxi-requests-" + customer.getIdentifier();
        kafkaTemplate.send(topicName, message);
    }

    /**
     * Publishes a message to a taxi's Kafka topic.
     *
     * @param taxi the taxi to which the message is addressed
     * @param customerStatusDto the customer status data transfer object
     */
    public void publishToTaxi(Taxi taxi, CustomerStatusDto customerStatusDto) {
        String topicName = "taxi-start-service-" + taxi.getIdentifier();

        String message = MappingUtils.map(customerStatusDto);
        kafkaTemplate.send(topicName, message);
    }

    /**
     * Assigns a taxi to a client.
     *
     * @param customer the customer requesting the taxi
     * @param destination the destination of the customer
     * @return true if a taxi was successfully assigned, false otherwise
     */
    private boolean assignTaxiToClient(Customer customer, String destination) {
        log.info("Asignando taxi para el cliente {} hacia {}", customer.getIdentifier(), destination);

        List<Taxi> availableTaxis = taxiRepository.findAllByAvailable(true);

        for (Taxi taxi : availableTaxis) {
            if (clientHandler.getConnectedTaxis().containsKey(taxi.getIdentifier())) {
                if (clientHandler.getConnectedTaxis().get(taxi.getIdentifier()).isConnected()) {
                    Optional<Location> location = locationRepository.findByIdentifier(destination);
                    if (location.isEmpty()) {
                        return false;
                    }

                    taxi.setAvailable(false);
                    taxi.setDestIdentifier(customer.getIdentifier());
                    taxi.setState(TaxiState.ASSIGNED); //asignat
                    taxiRepository.save(taxi);

                    CustomerStatusDto customerStatusDto = CustomerStatusDto.builder()
                                                                  .customerX(customer.getX())
                                                                  .customerY(customer.getY())
                                                                  .x(taxi.getX())
                                                                  .y(taxi.getY())
                                                                  .taxiId(taxi.getIdentifier())
                                                                  .status(taxi.getState())
                                                                  .destX(location.get().getX())
                                                                  .destY(location.get().getY())
                                                                  .build();
                    publishToTaxi(taxi, customerStatusDto);
                    customerTaxiAssignmentService.createCustomerTaxiAssignment(customer.getId(), taxi.getId());
                    return true;
                }
            }
        }
        log.error("NOT CONTAINS");
        return false;
    }

    /**
     * Listens for taxi directions from Kafka.
     *
     * @param taxiStatusMessage the message containing the taxi status
     */
    @KafkaListener(topics = "taxi-directions", groupId = "group", containerFactory = "kafkaListenerContainerFactory")
    public void listenTaxiDirections(String taxiStatusMessage) {
        log.info("Received Taxi Status Update: {}", taxiStatusMessage);
        // Crear un nuevo objeto TaxiStatusDto usando el constructor
        TaxiStatusDto taxiStatusDto = MappingUtils.mapFromString(taxiStatusMessage, TaxiStatusDto.class);

        // Actualizar la localización del taxi en tu sistema
        taxiService.updateTaxiLocationByIdentifier(taxiStatusDto);

        int x = taxiStatusDto.getX();
        int y = taxiStatusDto.getY();
        Taxi taxi = taxiRepository.findTaxiByIdentifier(taxiStatusDto.getTaxiId());

        switch (taxiStatusDto.getStatus()) {
            case TaxiState.STOPPED -> cityMap.updatePosition(x, y, cityMap.getPosition(x, y).data(), CityMap.Color.RED);

            case TaxiState.EN_ROUTE_TO_DESTINATION -> {
                Optional<CustomerTaxiAssignment> taxiAssignment = customerTaxiAssignmentRepository.findByIdTaxiId(taxi.getId());
                if (taxiAssignment.isPresent()) {
                    Long customerId = taxiAssignment.get().getId().getCustomerId();
                    Customer customer = customerRepository.findById(customerId).orElseThrow();
                    customer.setX(x);
                    customer.setY(y);
                    customerRepository.save(customer);
                    cityMap.updatePosition(x, y, taxiStatusDto.getTaxiId() + customer.getDestIdentifier(), CityMap.Color.GREEN);
                } else{
                    log.error("Taxi assignment not found for taxi {}", taxi.getIdentifier());
                }
            }
            case TaxiState.RETURNING_TO_BASE -> {
                if (taxi.getX() == 1 && taxi.getY() == 1) {
                    taxi.setAvailable(true);
                    taxi.setState(TaxiState.IDLE);
                    taxi.setDestIdentifier(null);
                }
                 taxiRepository.save(taxi);

            }

            case TaxiState.DESTINATION_REACHED -> {
                taxi.setState(TaxiState.RETURNING_TO_BASE);
                taxiRepository.save(taxi);
                CustomerStatusDto customerStatusDto = CustomerStatusDto.builder()
                        .customerX(-1)
                        .customerY(-1)
                        .x(taxi.getX())
                        .y(taxi.getY())
                        .taxiId(taxi.getIdentifier())
                        .status(taxi.getState())
                        .destX(-1)
                        .destY(-1)
                        .build();
                publishToTaxi(taxi, customerStatusDto);

                Optional<CustomerTaxiAssignment> taxiAssignment = customerTaxiAssignmentRepository.findByIdTaxiId(taxi.getId());
                if (taxiAssignment.isPresent()) {
                    Long customerId = taxiAssignment.get().getId().getCustomerId();
                    Customer customer = customerRepository.findById(customerId).orElseThrow();
                    publishToClient(customer, "END");
                } else {
                    log.error("Taxi assignment not found for taxi {}", taxi.getIdentifier());
                }
            }
            case TaxiState.PICKUP -> {
                taxi.setState(TaxiState.EN_ROUTE_TO_DESTINATION);
                taxiRepository.save(taxi);
            }
        }

        log.info("Taxi {} location updated to [{}, {}]",
                taxiStatusDto.getTaxiId(), taxiStatusDto.getX(), taxiStatusDto.getY());
    }

    /**
     * Logs a message indicating that a Kafka message was sent.
     *
     * @param message the message that was sent
     * @param topic the topic to which the message was sent
     */
    private void logKafkaSend(String message, String topic) {
        log.info("[Topic: {}] {}", topic, message);
    }

    /**
     * Populates the city map with initial data.
     *
     * @return the populated city map
     */
    private CityMap populateMap() {
        CityMap map = new CityMap();

        // Primero añadimos las localizaciones (monumentos) al mapa
        List<Location> locations = locationRepository.findAll();
        for (Location loc : locations) {
            int x = loc.getX();
            int y = loc.getY();
            String identifier = loc.getIdentifier();

            // Obtenemos el valor actual de la celda y lo concatenamos con el nuevo identificador
            String currentContent = map.getPosition(x, y).data();  // Asumiendo que el método getPositionContent existe
            String updatedContent = currentContent.isEmpty() ? identifier : currentContent + ", " + identifier;

            // Actualizamos el mapa en la posición (x, y) con el contenido concatenado
            map.updatePosition(x, y, updatedContent, CityMap.Color.YELLOW);
        }

        // Luego añadimos los clientes al mapa
        List<Customer> customers = customerRepository.findAll();
        for (Customer customer : customers) {
            int x = customer.getX();
            int y = customer.getY();
            String identifier = customer.getIdentifier();

            // Concatenamos el contenido actual con el identificador del cliente
            String currentContent =  map.getPosition(x, y).data();
            String updatedContent = currentContent.isEmpty() ? identifier : currentContent + ", " + identifier;

            // Actualizamos el mapa en la posición (x, y) con el contenido concatenado
            map.updatePosition(x, y, updatedContent, CityMap.Color.BLUE);
        }

        // Finalmente añadimos los taxis al mapa
        List<Taxi> taxis = taxiRepository.findAll();
        for (Taxi taxi : taxis) {
            int x = taxi.getX();
            int y = taxi.getY();
            String identifier = taxi.getIdentifier();

            // Concatenamos el contenido actual con el identificador del taxi
            String currentContent = map.getPosition(x, y).data();
            String updatedContent = currentContent.isEmpty() ? identifier : currentContent + ", " + identifier;

            // Dependiendo del estado del taxi, establecemos el color apropiado
            CityMap.Color color = (taxi.getState() == TaxiState.STOPPED) ? CityMap.Color.RED : CityMap.Color.GREEN;
            map.updatePosition(x, y, updatedContent, color);
        }

        return map;
    }
}