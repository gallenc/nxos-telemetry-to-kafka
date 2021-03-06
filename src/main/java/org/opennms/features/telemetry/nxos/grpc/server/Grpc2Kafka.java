package org.opennms.features.telemetry.nxos.grpc.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.commons.cli.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * The Class Grpc2Kafka.
 * 
 * @author <a href="mailto:agalue@opennms.org">Alejandro Galue</a>
 */
public class Grpc2Kafka {

    /** The Constant LOG. */
    private static final Logger LOG = LoggerFactory.getLogger(Grpc2Kafka.class);

    /** The Constant DEFAULT_GRPC_PORT. */
    public static final int DEFAULT_GRPC_PORT = 50051;
    
    /** The Constant DEFAULT_KAFKA_BOOTSTRAP. */
    public static final String DEFAULT_KAFKA_BOOTSTRAP = "127.0.0.1:9092";
    
    /** The Constant DEFAULT_KAFKA_TOPIC. */
    public static final String DEFAULT_KAFKA_TOPIC = "nxos-telemetry-grpc";

    /**
     * The main method.
     *
     * @param args the arguments
     * @throws IOException Signals that an I/O exception has occurred.
     * @throws InterruptedException the interrupted exception
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        final Options options = new Options()
                .addOption("p", "port", true, "gRPC server listener port.\nDefault: " + DEFAULT_GRPC_PORT)
                .addOption("b", "bootstrap-servers", true, "Kafka bootstrap server list.\nDefault: " + DEFAULT_KAFKA_BOOTSTRAP)
                .addOption("t", "topic", true, "Kafka destination topic name.\nDefault: " + DEFAULT_KAFKA_TOPIC)
                .addOption("d", "debug", false, "Show message on logs.")
                .addOption("h", "help", false, "Show this help.");

        final CommandLineParser cmdLineParser = new DefaultParser();
        CommandLine cli = null;
        try {
            cli = cmdLineParser.parse(options, args);
        } catch (ParseException parseException) {
            System.err.printf("ERROR: Unable to parse command-line arguments due to: %s\n", parseException);
            printHelp(options);
            System.exit(0);
        }

        if (cli.hasOption('h')) {
            printHelp(options);
            System.exit(1);
        }

        String kafkaServers = cli.hasOption('b') ? cli.getOptionValue('b') : DEFAULT_KAFKA_BOOTSTRAP;
        String kafkaTopic = cli.hasOption('t') ? cli.getOptionValue('t') : DEFAULT_KAFKA_TOPIC;
        Integer serverPort = cli.hasOption('p') ? new Integer(cli.getOptionValue('p')) : DEFAULT_GRPC_PORT;

        KafkaProducer<String, byte[]> producer = buildProducer(kafkaServers);
        Server server = buildServer(serverPort, producer, kafkaTopic, cli.hasOption('d'));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Received shutdown request...");
            producer.close(10, TimeUnit.SECONDS);
            server.shutdown();
            System.out.println("Successfully stopped the server.");
        }));

        server.awaitTermination();
    }

    /**
     * Prints the help.
     *
     * @param options the CLI options
     */
    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("grpc2kafka", options);
    }

    /**
     * Builds the producer.
     *
     * @param kafkaServers the Kafka bootstrap servers string
     * @return the Kafka producer
     */
    public static KafkaProducer<String, byte[]> buildProducer(final String kafkaServers) {
        Properties producerConfig = new Properties();
        producerConfig.setProperty("bootstrap.servers", kafkaServers);
        producerConfig.setProperty("key.serializer", StringSerializer.class.getName());
        producerConfig.setProperty("value.serializer", ByteArraySerializer.class.getName());
        producerConfig.setProperty("acks", "1");
        producerConfig.setProperty("retries", "3");

        LOG.info("Connecting to Kafka cluster using {}...", kafkaServers);
        return new KafkaProducer<String, byte[]>(producerConfig);
    }

    /**
     * Builds the gRPC server.
     *
     * @param serverPort the server port
     * @param producer the Kafka producer
     * @param kafkaTopic the Kafka topic
     * @param debug the debug flag
     * @return the gRPC server
     */
    public static Server buildServer(final int serverPort, final KafkaProducer<String, byte[]> producer, final String kafkaTopic, final boolean debug) {
        LOG.info("Starting NX-OS gRPC server without TLS on port {}...", serverPort);
        return ServerBuilder.forPort(serverPort)
                .addService(new NxosMdtDialoutService(producer, kafkaTopic, debug))
                .build();
    }

}
