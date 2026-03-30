package com.thecookiezen.ladybugdb.spring.connection;

import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple connection factory that creates a new connection for each request.
 * Useful for testing or single-threaded applications.
 */
public class SimpleConnectionFactory implements LadybugDBConnectionFactory {

    private static final Logger logger = LoggerFactory.getLogger(SimpleConnectionFactory.class);

    private final Database database;
    private final String homeDirectory;

    public SimpleConnectionFactory(Database database) {
        this(database, null);
    }

    public SimpleConnectionFactory(Database database, String homeDirectory) {
        this.database = database;
        this.homeDirectory = homeDirectory;
    }

    @Override
    public Connection getConnection() {
        logger.debug("Creating new connection");
        Connection connection = new Connection(database);
        configureHomeDirectory(connection);
        return connection;
    }

    private void configureHomeDirectory(Connection connection) {
        if (homeDirectory != null && !homeDirectory.isEmpty()) {
            try (var result = connection.query("CALL home_directory='" + homeDirectory + "'")) {
                if (!result.isSuccess()) {
                    throw new LadybugDBConnectionException(
                            "Failed to set home_directory to '" + homeDirectory + "': " + result.getErrorMessage());
                }
            } catch (LadybugDBConnectionException e) {
                throw e;
            } catch (Exception e) {
                throw new LadybugDBConnectionException(
                        "Failed to set home_directory to '" + homeDirectory + "'", e);
            }
        }
    }

    @Override
    public void releaseConnection(Connection connection) {
        if (connection != null) {
            logger.debug("Closing connection");
            connection.close();
        }
    }

    @Override
    public Database getDatabase() {
        return database;
    }

    @Override
    public void close() {
        // Nothing to close for simple factory
    }
}
