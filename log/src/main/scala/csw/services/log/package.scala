package csw.services

/**
 * == Log Service ==
 *
 * The Log Service package contains the config files for logging and optional, external applications
 * (Logstash, Elasticsearch, Kibana) to view and process the log information.
 *
 * A special logger PrefixedActorLogging is
 * provided for Akka actors that implement TMT components, such as HCDs and assemblies.
 * It inserts an [[http://logback.qos.ch/manual/mdc.html MDC]] prefix field into the log, where prefix
 * is the component prefix, which is made up of the subsystem name, followed by a dot and the rest of the component prefix.
 *
 * The standard logging framework used here is `slf4j` and `logback`. For packages that require `log4j`
 * (like OPC UA), there is a bridge: `log4j-over-slf4j` that can be used instead of the log4j dependency.
 *
 * === Configuring Logging ===
 *
 * Applications that wish to log can add this project as a dependency, so that the logback.xml config file
 * will be found. This configures logging to go to the console and, if the system property "application-name" is
 * defined, to application-name.log in the current directory.
 */
package object log {

}
