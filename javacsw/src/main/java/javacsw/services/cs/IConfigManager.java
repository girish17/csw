package javacsw.services.cs;

import csw.services.cs.core.*;
import scala.Unit;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Defines an asynchronous (non-blocking) java interface for storing and retrieving configuration information
 */
@SuppressWarnings("unused")
public interface IConfigManager {

    /**
     * Returns a reference to the underlying scala ConfigManager
     * @return the ConfigManager
     */
    ConfigManager getManager();

    /**
     * Creates a config file with the given path and data and optional comment.
     * An IOException is thrown if the file already exists.
     *
     * @param path       the config file path
     * @param configData the contents of the file
     * @param oversize   true if the file is large and requires special handling (external storage)
     * @param comment    a comment to associate with this file
     * @return a future unique id that can be used to refer to the file
     */
    CompletableFuture<ConfigId> create(File path, ConfigData configData, Boolean oversize, String comment);

    /**
     * Updates the config file with the given path and data and optional comment.
     * An FileNotFoundException is thrown if the file does not exists.
     *
     * @param path       the config file path
     * @param configData the contents of the file
     * @param comment    a comment to associate with this file
     * @return a future unique id that can be used to refer to the file
     */
    CompletableFuture<ConfigId> update(File path, ConfigData configData, String comment);

    /**
     * Creates a config file with the given path and data and optional comment,
     * or updates it, if it already exists.
     *
     * @param path       the config file path
     * @param configData the contents of the file
     * @param oversize   true if the file is large and requires special handling (external storage)
     * @param comment    a comment to associate with this file
     * @return a future unique id that can be used to refer to the file
     */
    CompletableFuture<ConfigId> createOrUpdate(File path, ConfigData configData, Boolean oversize, String comment);

    /**
     * Gets and returns the latest version of the config file stored under the given path.
     *
     * @param path the configuration path
     * @return a future  object containing the configuration data, if found
     */
    CompletableFuture<Optional<IConfigData>> get(File path);

    /**
     * Gets and returns the config file stored under the given path.
     *
     * @param path the configuration path
     * @param id   id used to specify a specific version to fetch
     * @return a future object containing the configuration data, if found
     */
    CompletableFuture<Optional<IConfigData>> get(File path, ConfigId id);

    /**
     * Gets the file as it existed on the given date.
     * If date is before the file was created, the initial version is returned.
     * If date is after the last change, the most recent version is returned.
     * If the path does not exist in the repo, an empty value is returned.
     *
     * @param path the file path relative to the repository root
     * @param date the target date
     * @return a future object that can be used to access the file's data, if found
     */
    CompletableFuture<Optional<IConfigData>> get(File path, Date date);

    /**
     * Returns true if the given path exists and is being managed
     *
     * @param path the configuration path
     * @return true if the file exists
     */
    CompletableFuture<Boolean> exists(File path);

    /**
     * Deletes the given config file (older versions will still be available)
     *
     * @param path the configuration path
     * @return a future indicating when done
     */
    CompletableFuture<Unit> delete(File path);

    /**
     * Deletes the given config file (older versions will still be available)
     *
     * @param path    the configuration path
     * @param comment comment for the delete operation
     * @return a future indicating when done
     */
    CompletableFuture<Unit> delete(File path, String comment);

    /**
     * Returns a list containing all known configuration files
     *
     * @return a list containing one ConfigFileInfo object for each known config file
     */
    CompletableFuture<List<ConfigFileInfo>> list();

    /**
     * Returns a list of all known versions of a given path
     *
     * @param path the relative path in the repo
     * @return a list containing one ConfigFileHistory object for each version of path
     */
    CompletableFuture<List<ConfigFileHistory>> history(File path);
}